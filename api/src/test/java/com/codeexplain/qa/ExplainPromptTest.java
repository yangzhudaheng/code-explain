package com.codeexplain.qa;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExplainPromptTest {

    @Test
    @DisplayName("每行都带行号，且行号与原文行对齐")
    void numbersEveryLine() {
        String code = "class A {\n  int x = 1;\n}";

        String numbered = ExplainPrompt.withLineNumbers(code, 2, 2);

        assertThat(numbered).isEqualTo("1| class A {\n2|   int x = 1;\n3| }");
    }

    @Test
    @DisplayName("空行也占一行，行号不会跳过")
    void keepsBlankLines() {
        String numbered = ExplainPrompt.withLineNumbers("a\n\nb", 1, 1);

        assertThat(numbered).isEqualTo("1| a\n2| \n3| b");
    }

    @Test
    @DisplayName("超长代码只截选中区域附近的窗口，并标注省略了多少行")
    void truncatesLongCodeAroundSelection() {
        // 2000 行 × 5 字符 ≈ 10KB，超过 6000 字符的上下文上限才会触发裁剪
        String code = String.join("\n", java.util.Collections.nCopies(2000, "line"));

        String numbered = ExplainPrompt.withLineNumbers(code, 1000, 1000);

        // 窗口是选中行上下各 40 行：959 行之前与 1040 行之后都被裁掉
        assertThat(numbered).startsWith("...（前 959 行已省略）");
        assertThat(numbered).contains("1000| line").endsWith("...（后 960 行已省略）");
    }

    @Test
    @DisplayName("提示词里同时含问题、选中片段与代码")
    void buildsUserMessage() {
        var messages = ExplainPrompt.messages("java", "Solution.java",
                "class A {\n  int x = 1;\n}", 2, 2, "int x = 1;", "这行有什么用？");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(1).content())
                .contains("Solution.java")
                .contains("java")
                .contains("2|   int x = 1;")
                .contains("这行有什么用？");
    }

    @Test
    @DisplayName("只有一行时不写 3~5 这种冗余区间")
    void singleLineRangeHasNoDash() {
        var messages = ExplainPrompt.messages(null, null, "x", 7, 7, "x", "?");

        assertThat(messages.get(1).content()).contains("第 7 行").doesNotContain("7~7");
    }
}
