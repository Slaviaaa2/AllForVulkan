package dev.allforvulkan.framegraph;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Fullscreen triangle and a sampled grade. Words are SPIR-V 1.0, validated by spirv-val when present. */
public final class CompositeShaders {
    private CompositeShaders() {}

    public static int[] vertex() {
        var spirv = new Spirv();
        spirv.op(17, 1);
        spirv.op(14, 0, 1);
        int main = spirv.id();
        int vertexIndex = spirv.id();
        int position = spirv.id();
        int uv = spirv.id();
        spirv.entry(0, main, "main", vertexIndex, position, uv);
        spirv.op(71, vertexIndex, 11, 42);
        spirv.op(71, position, 11, 0);
        spirv.op(71, uv, 30, 0);
        int voidType = spirv.id();
        int functionType = spirv.id();
        int floatType = spirv.id();
        int vec4 = spirv.id();
        int vec2 = spirv.id();
        int intType = spirv.id();
        int inputInt = spirv.id();
        int outputVec4 = spirv.id();
        int outputVec2 = spirv.id();
        int one = spirv.id();
        int two = spirv.id();
        int zeroFloat = spirv.id();
        int oneFloat = spirv.id();
        int twoFloat = spirv.id();
        int halfFloat = spirv.id();
        int ones = spirv.id();
        spirv.op(19, voidType);
        spirv.op(33, functionType, voidType);
        spirv.op(22, floatType, 32);
        spirv.op(23, vec4, floatType, 4);
        spirv.op(23, vec2, floatType, 2);
        spirv.op(21, intType, 32, 1);
        spirv.op(32, inputInt, 1, intType);
        spirv.op(32, outputVec4, 3, vec4);
        spirv.op(32, outputVec2, 3, vec2);
        spirv.op(43, intType, one, 1);
        spirv.op(43, intType, two, 2);
        spirv.op(43, floatType, zeroFloat, Float.floatToRawIntBits(0f));
        spirv.op(43, floatType, oneFloat, Float.floatToRawIntBits(1f));
        spirv.op(43, floatType, twoFloat, Float.floatToRawIntBits(2f));
        spirv.op(43, floatType, halfFloat, Float.floatToRawIntBits(0.5f));
        spirv.op(44, vec2, ones, oneFloat, oneFloat);
        spirv.op(59, inputInt, vertexIndex, 1);
        spirv.op(59, outputVec4, position, 3);
        spirv.op(59, outputVec2, uv, 3);
        spirv.op(54, voidType, main, 0, functionType);
        int label = spirv.id();
        spirv.op(248, label);
        int index = spirv.id();
        spirv.op(61, intType, index, vertexIndex);
        int shifted = spirv.id();
        spirv.op(196, intType, shifted, index, one);
        int bitsX = spirv.id();
        spirv.op(199, intType, bitsX, shifted, two);
        int bitsY = spirv.id();
        spirv.op(199, intType, bitsY, index, two);
        int x = spirv.id();
        spirv.op(111, floatType, x, bitsX);
        int y = spirv.id();
        spirv.op(111, floatType, y, bitsY);
        int corner = spirv.id();
        spirv.op(80, vec2, corner, x, y);
        int scaled = spirv.id();
        spirv.op(142, vec2, scaled, corner, twoFloat);
        int clip = spirv.id();
        spirv.op(131, vec2, clip, scaled, ones);
        int point = spirv.id();
        spirv.op(80, vec4, point, clip, zeroFloat, oneFloat);
        spirv.op(62, position, point);
        int texcoord = spirv.id();
        spirv.op(142, vec2, texcoord, corner, halfFloat);
        spirv.op(62, uv, texcoord);
        spirv.op(253);
        spirv.op(56);
        return spirv.words();
    }

    public static int[] fragment() {
        var spirv = new Spirv();
        spirv.op(17, 1);
        spirv.op(14, 0, 1);
        int main = spirv.id();
        int uv = spirv.id();
        int outColor = spirv.id();
        int sampler = spirv.id();
        int uniform = spirv.id();
        int block = spirv.id();
        spirv.entry(4, main, "main", uv, outColor);
        spirv.op(16, main, 7);
        spirv.op(71, uv, 30, 0);
        spirv.op(71, outColor, 30, 0);
        // Decoration 33 is Binding and 34 is DescriptorSet.
        spirv.op(71, uniform, 33, 0);
        spirv.op(71, uniform, 34, 0);
        spirv.op(71, sampler, 33, 1);
        spirv.op(71, sampler, 34, 0);
        spirv.op(71, block, 2);
        spirv.op(72, block, 0, 35, 0);
        int voidType = spirv.id();
        int functionType = spirv.id();
        int floatType = spirv.id();
        int vec4 = spirv.id();
        int vec2 = spirv.id();
        int intType = spirv.id();
        int image = spirv.id();
        int sampled = spirv.id();
        int samplerPointer = spirv.id();
        int uniformPointer = spirv.id();
        int factorPointer = spirv.id();
        int inputVec2 = spirv.id();
        int outputVec4 = spirv.id();
        int zero = spirv.id();
        spirv.op(19, voidType);
        spirv.op(33, functionType, voidType);
        spirv.op(22, floatType, 32);
        spirv.op(23, vec4, floatType, 4);
        spirv.op(23, vec2, floatType, 2);
        spirv.op(21, intType, 32, 1);
        spirv.op(25, image, floatType, 1, 0, 0, 0, 1, 0);
        spirv.op(27, sampled, image);
        spirv.op(32, samplerPointer, 0, sampled);
        spirv.op(30, block, vec4);
        spirv.op(32, uniformPointer, 2, block);
        spirv.op(32, factorPointer, 2, vec4);
        spirv.op(32, inputVec2, 1, vec2);
        spirv.op(32, outputVec4, 3, vec4);
        spirv.op(43, intType, zero, 0);
        spirv.op(59, inputVec2, uv, 1);
        spirv.op(59, outputVec4, outColor, 3);
        spirv.op(59, samplerPointer, sampler, 0);
        spirv.op(59, uniformPointer, uniform, 2);
        spirv.op(54, voidType, main, 0, functionType);
        int label = spirv.id();
        spirv.op(248, label);
        int coordinate = spirv.id();
        spirv.op(61, vec2, coordinate, uv);
        int texture = spirv.id();
        spirv.op(61, sampled, texture, sampler);
        int sampledColor = spirv.id();
        spirv.op(87, vec4, sampledColor, texture, coordinate);
        int member = spirv.id();
        spirv.op(65, factorPointer, member, uniform, zero);
        int factor = spirv.id();
        spirv.op(61, vec4, factor, member);
        int graded = spirv.id();
        spirv.op(133, vec4, graded, sampledColor, factor);
        spirv.op(62, outColor, graded);
        spirv.op(253);
        spirv.op(56);
        return spirv.words();
    }

    static final class Spirv {
        private final ArrayList<Integer> body = new ArrayList<>();
        private int bound = 1;

        int id() { return bound++; }

        void op(int opcode, int... operands) {
            body.add((operands.length + 1) << 16 | opcode);
            for (int operand : operands) body.add(operand);
        }

        void entry(int model, int function, String name, int... interfaces) {
            byte[] raw = (name + "\0").getBytes(StandardCharsets.UTF_8);
            int stringWords = (raw.length + 3) / 4;
            int wordCount = 3 + stringWords + interfaces.length;
            body.add(wordCount << 16 | 15);
            body.add(model);
            body.add(function);
            for (int word = 0; word < stringWords; word++) {
                int value = 0;
                for (int byteIndex = 0; byteIndex < 4; byteIndex++) {
                    int source = word * 4 + byteIndex;
                    if (source < raw.length) value |= (raw[source] & 0xff) << (8 * byteIndex);
                }
                body.add(value);
            }
            for (int iface : interfaces) body.add(iface);
        }

        int[] words() {
            int[] module = new int[5 + body.size()];
            module[0] = 0x07230203;
            module[1] = 0x00010000;
            module[3] = bound;
            for (int i = 0; i < body.size(); i++) module[5 + i] = body.get(i);
            return module;
        }
    }
}
