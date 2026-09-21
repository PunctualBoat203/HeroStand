package com.herostand.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.AgeableListModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Allocation-free renderer for adult HumanoidModel geometry used by HeroStand's manual Palladium
 * paths.
 *
 * Vanilla 1.20.1 ModelPart rendering allocates a new Pose + Matrix4f + Matrix3f on every
 * pushPose(), a Quaternionf for rotated parts, a Vector3f per polygon normal, and a Vector4f per
 * emitted vertex. Complex superhero models multiplied by dozens of stands can therefore generate
 * hundreds of MiB/s of temporary garbage.
 *
 * We extract immutable cube/UV data once per ModelPart identity, then replay the exact same
 * transforms using reusable matrix/vector scratch objects. Models that override renderToBuffer()
 * are deliberately rejected so custom renderer semantics remain untouched.
 */
final class FastHumanoidModelRenderer {
    private static final FastHumanoidModelRenderer INSTANCE =
            new FastHumanoidModelRenderer();

    private final IdentityHashMap<ModelPart, PartNode> partCache = new IdentityHashMap<>();
    private final IdentityHashMap<Class<?>, Boolean> classEligibility = new IdentityHashMap<>();

    private final Field cubesField;
    private final Field childrenField;
    private final Field polygonsField;
    private final Field polygonVerticesField;
    private final Field polygonNormalField;
    private final Field vertexPositionField;
    private final Field vertexUField;
    private final Field vertexVField;

    private Matrix4f[] poseScratch = new Matrix4f[24];
    private Matrix3f[] normalScratch = new Matrix3f[24];
    private Quaternionf[] rotationScratch = new Quaternionf[24];

    private final Vector4f positionScratch = new Vector4f();
    private final Vector3f normalVectorScratch = new Vector3f();

    private boolean healthy;

    private FastHumanoidModelRenderer() {
        Field cubes = null;
        Field children = null;
        Field polygons = null;
        Field polyVertices = null;
        Field polyNormal = null;
        Field vertexPos = null;
        Field vertexU = null;
        Field vertexV = null;
        boolean ok = false;

        try {
            cubes = accessible(ModelPart.class.getDeclaredField("cubes"));
            children = accessible(ModelPart.class.getDeclaredField("children"));

            Class<?> cubeClass =
                    Class.forName("net.minecraft.client.model.geom.ModelPart$Cube");
            Class<?> polygonClass =
                    Class.forName("net.minecraft.client.model.geom.ModelPart$Polygon");
            Class<?> vertexClass =
                    Class.forName("net.minecraft.client.model.geom.ModelPart$Vertex");

            polygons = accessible(cubeClass.getDeclaredField("polygons"));
            polyVertices = accessible(polygonClass.getDeclaredField("vertices"));
            polyNormal = accessible(polygonClass.getDeclaredField("normal"));
            vertexPos = accessible(vertexClass.getDeclaredField("pos"));
            vertexU = accessible(vertexClass.getDeclaredField("u"));
            vertexV = accessible(vertexClass.getDeclaredField("v"));

            for (int i = 0; i < poseScratch.length; i++) {
                poseScratch[i] = new Matrix4f();
                normalScratch[i] = new Matrix3f();
                rotationScratch[i] = new Quaternionf();
            }

            ok = true;
        } catch (Throwable ignored) {
        }

        this.cubesField = cubes;
        this.childrenField = children;
        this.polygonsField = polygons;
        this.polygonVerticesField = polyVertices;
        this.polygonNormalField = polyNormal;
        this.vertexPositionField = vertexPos;
        this.vertexUField = vertexU;
        this.vertexVField = vertexV;
        this.healthy = ok;
    }

    static boolean render(HumanoidModel<?> model,
                          PoseStack poseStack,
                          VertexConsumer consumer,
                          int packedLight,
                          int packedOverlay,
                          float red,
                          float green,
                          float blue,
                          float alpha) {
        return INSTANCE.renderInternal(
                model, poseStack, consumer,
                packedLight, packedOverlay,
                red, green, blue, alpha
        );
    }

    static void clearCache() {
        INSTANCE.partCache.clear();
        INSTANCE.classEligibility.clear();
        INSTANCE.healthy = INSTANCE.cubesField != null;
    }

    private boolean renderInternal(HumanoidModel<?> model,
                                   PoseStack poseStack,
                                   VertexConsumer consumer,
                                   int packedLight,
                                   int packedOverlay,
                                   float red,
                                   float green,
                                   float blue,
                                   float alpha) {
        if (!healthy || model == null || model.young || !eligibleClass(model.getClass())) {
            return false;
        }

        try {
            Matrix4f basePose = poseStack.last().pose();
            Matrix3f baseNormal = poseStack.last().normal();

            renderRoot(model.head, basePose, baseNormal, consumer,
                    packedLight, packedOverlay, red, green, blue, alpha);
            renderRoot(model.body, basePose, baseNormal, consumer,
                    packedLight, packedOverlay, red, green, blue, alpha);
            renderRoot(model.rightArm, basePose, baseNormal, consumer,
                    packedLight, packedOverlay, red, green, blue, alpha);
            renderRoot(model.leftArm, basePose, baseNormal, consumer,
                    packedLight, packedOverlay, red, green, blue, alpha);
            renderRoot(model.rightLeg, basePose, baseNormal, consumer,
                    packedLight, packedOverlay, red, green, blue, alpha);
            renderRoot(model.leftLeg, basePose, baseNormal, consumer,
                    packedLight, packedOverlay, red, green, blue, alpha);
            renderRoot(model.hat, basePose, baseNormal, consumer,
                    packedLight, packedOverlay, red, green, blue, alpha);
            return true;
        } catch (Throwable failure) {
            healthy = false;
            partCache.clear();
            return false;
        }
    }

    private boolean eligibleClass(Class<?> modelClass) {
        Boolean cached = classEligibility.get(modelClass);
        if (cached != null) return cached;

        boolean eligible = false;
        try {
            Method method = modelClass.getMethod(
                    "renderToBuffer",
                    PoseStack.class,
                    VertexConsumer.class,
                    int.class,
                    int.class,
                    float.class,
                    float.class,
                    float.class,
                    float.class
            );

            /*
             * HumanoidModel inherits the standard implementation from AgeableListModel. If an
             * add-on overrides renderToBuffer(), it may render extra top-level parts or use custom
             * state; leave those models on their original renderer.
             */
            eligible = method.getDeclaringClass() == AgeableListModel.class;
        } catch (Throwable ignored) {
        }

        classEligibility.put(modelClass, eligible);
        return eligible;
    }

    private void renderRoot(ModelPart root,
                            Matrix4f basePose,
                            Matrix3f baseNormal,
                            VertexConsumer consumer,
                            int packedLight,
                            int packedOverlay,
                            float red,
                            float green,
                            float blue,
                            float alpha) throws IllegalAccessException {
        if (root == null || !root.visible) return;

        ensureDepth(1);
        poseScratch[0].set(basePose);
        normalScratch[0].set(baseNormal);

        PartNode node = partCache.get(root);
        if (node == null) {
            node = buildNode(root);
            partCache.put(root, node);
        }

        renderNode(
                node, 1,
                poseScratch[0], normalScratch[0],
                consumer,
                packedLight, packedOverlay,
                red, green, blue, alpha
        );
    }

    private void renderNode(PartNode node,
                            int depth,
                            Matrix4f parentPose,
                            Matrix3f parentNormal,
                            VertexConsumer consumer,
                            int packedLight,
                            int packedOverlay,
                            float red,
                            float green,
                            float blue,
                            float alpha) {
        ModelPart part = node.part;
        if (!part.visible) return;

        ensureDepth(depth + 1);

        Matrix4f pose = poseScratch[depth].set(parentPose);
        Matrix3f normal = normalScratch[depth].set(parentNormal);

        applyPartTransform(part, pose, normal, rotationScratch[depth]);

        if (!part.skipDraw) {
            for (PolygonData polygon : node.polygons) {
                renderPolygon(
                        polygon, pose, normal, consumer,
                        packedLight, packedOverlay,
                        red, green, blue, alpha
                );
            }
        }

        for (PartNode child : node.children) {
            renderNode(
                    child, depth + 1,
                    pose, normal,
                    consumer,
                    packedLight, packedOverlay,
                    red, green, blue, alpha
            );
        }
    }

    private void renderPolygon(PolygonData polygon,
                               Matrix4f pose,
                               Matrix3f normalMatrix,
                               VertexConsumer consumer,
                               int packedLight,
                               int packedOverlay,
                               float red,
                               float green,
                               float blue,
                               float alpha) {
        normalVectorScratch.set(polygon.normalX, polygon.normalY, polygon.normalZ);
        normalMatrix.transform(normalVectorScratch);

        float nx = normalVectorScratch.x();
        float ny = normalVectorScratch.y();
        float nz = normalVectorScratch.z();

        for (VertexData vertex : polygon.vertices) {
            positionScratch.set(vertex.x, vertex.y, vertex.z, 1.0F);
            pose.transform(positionScratch);

            consumer.vertex(
                    positionScratch.x(),
                    positionScratch.y(),
                    positionScratch.z(),
                    red, green, blue, alpha,
                    vertex.u, vertex.v,
                    packedOverlay,
                    packedLight,
                    nx, ny, nz
            );
        }
    }

    private static void applyPartTransform(ModelPart part,
                                           Matrix4f pose,
                                           Matrix3f normal,
                                           Quaternionf rotation) {
        pose.translate(part.x / 16.0F, part.y / 16.0F, part.z / 16.0F);

        if (part.xRot != 0.0F || part.yRot != 0.0F || part.zRot != 0.0F) {
            rotation.rotationZYX(part.zRot, part.yRot, part.xRot);
            pose.rotate(rotation);
            normal.rotate(rotation);
        }

        float sx = part.xScale;
        float sy = part.yScale;
        float sz = part.zScale;

        if (sx != 1.0F || sy != 1.0F || sz != 1.0F) {
            pose.scale(sx, sy, sz);

            if (sx == sy && sy == sz) {
                if (sx < 0.0F) {
                    normal.scale(-1.0F);
                }
            } else {
                float ix = 1.0F / sx;
                float iy = 1.0F / sy;
                float iz = 1.0F / sz;
                float correction = Mth.fastInvCubeRoot(ix * iy * iz);
                normal.scale(correction * ix, correction * iy, correction * iz);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private PartNode buildNode(ModelPart part) throws IllegalAccessException {
        List<?> cubes = (List<?>) cubesField.get(part);
        Map<String, ModelPart> children = (Map<String, ModelPart>) childrenField.get(part);

        int polygonCount = 0;
        for (Object cube : cubes) {
            Object[] polygons = (Object[]) polygonsField.get(cube);
            polygonCount += polygons.length;
        }

        PolygonData[] polygonData = new PolygonData[polygonCount];
        int index = 0;

        for (Object cube : cubes) {
            Object[] polygons = (Object[]) polygonsField.get(cube);
            for (Object polygon : polygons) {
                polygonData[index++] = buildPolygon(polygon);
            }
        }

        PartNode[] childNodes = new PartNode[children.size()];
        int childIndex = 0;
        for (ModelPart child : children.values()) {
            childNodes[childIndex++] = buildNode(child);
        }

        return new PartNode(part, polygonData, childNodes);
    }

    private PolygonData buildPolygon(Object polygon) throws IllegalAccessException {
        Object[] vertices = (Object[]) polygonVerticesField.get(polygon);
        Vector3f normal = (Vector3f) polygonNormalField.get(polygon);

        VertexData[] vertexData = new VertexData[vertices.length];
        for (int i = 0; i < vertices.length; i++) {
            Object vertex = vertices[i];
            Vector3f position = (Vector3f) vertexPositionField.get(vertex);

            vertexData[i] = new VertexData(
                    position.x() / 16.0F,
                    position.y() / 16.0F,
                    position.z() / 16.0F,
                    vertexUField.getFloat(vertex),
                    vertexVField.getFloat(vertex)
            );
        }

        return new PolygonData(
                normal.x(), normal.y(), normal.z(),
                vertexData
        );
    }

    private void ensureDepth(int required) {
        if (required <= poseScratch.length) return;

        int oldLength = poseScratch.length;
        int newLength = oldLength;
        while (newLength < required) {
            newLength *= 2;
        }

        Matrix4f[] newPoses = new Matrix4f[newLength];
        Matrix3f[] newNormals = new Matrix3f[newLength];
        Quaternionf[] newRotations = new Quaternionf[newLength];

        System.arraycopy(poseScratch, 0, newPoses, 0, oldLength);
        System.arraycopy(normalScratch, 0, newNormals, 0, oldLength);
        System.arraycopy(rotationScratch, 0, newRotations, 0, oldLength);

        for (int i = oldLength; i < newLength; i++) {
            newPoses[i] = new Matrix4f();
            newNormals[i] = new Matrix3f();
            newRotations[i] = new Quaternionf();
        }

        poseScratch = newPoses;
        normalScratch = newNormals;
        rotationScratch = newRotations;
    }

    private static Field accessible(Field field) {
        field.setAccessible(true);
        return field;
    }

    private record PartNode(
            ModelPart part,
            PolygonData[] polygons,
            PartNode[] children
    ) {}

    private record PolygonData(
            float normalX,
            float normalY,
            float normalZ,
            VertexData[] vertices
    ) {}

    private record VertexData(
            float x,
            float y,
            float z,
            float u,
            float v
    ) {}
}
