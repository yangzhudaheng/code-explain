package com.codeexplain.qa;

import java.util.ArrayList;
import java.util.List;

import com.codeexplain.ai.ChatClient.Message;

/**
 * 把「代码 + 选中片段 + 问题」拼成提示词。
 *
 * <p>提示词是这个产品唯一的「效果调节旋钮」，所以规则写在这里而不是散在 Service 里。
 *
 * <p>三条经验：
 * <ol>
 *   <li><b>带行号。</b>用户提问时看的是带行号的代码，回答里能说「第 5 行」才对得上；</li>
 *   <li><b>只给选中片段与必要上下文。</b>整段代码全塞进去，模型容易泛泛复述一遍；
 *       明确告诉它「用户选的是这几行」，回答才会聚焦；</li>
 *   <li><b>限制篇幅与格式。</b>回答框宽度只有 320px，几千字的长文在那里根本读不了。</li>
 * </ol>
 */
public final class ExplainPrompt {

    private ExplainPrompt() {
    }

    /** 代码上下文上限：超过就只取选中区域附近的窗口，否则长文件会把上下文吃满。 */
    private static final int MAX_CODE_CHARS = 6000;

    /** 选中区域上下各保留的行数（裁剪窗口时用）。 */
    private static final int WINDOW_LINES = 40;

    public static final String SYSTEM = """
            你是一名资深算法与编程讲师，正在帮学生看懂他贴在白板上的代码。

            回答要求：
            1. 用中文，先给一句结论，再分点解释；总长度控制在 500 字以内。
            2. 只解释学生选中的那几行在整段代码里的作用、为什么这么写、换成别的写法会怎样。
            3. 不要整段复述代码，需要引用时只贴必要的 1~3 行，用 Markdown 行内代码或代码块。
            4. 可以用「段落 / 有序或无序列表 / 加粗 / 行内代码 / 代码块」这几种 Markdown 语法，
               不要用表格、不要用一级标题。
            5. 如果代码本身有 bug 或边界问题，直接指出并给出修法。
            6. 如果学生的问题与代码无关，先用一句话回答，再说明它与这段代码的关系。
            """;

    /**
     * @param language  语言标识（java / cpp / python…），仅用于给模型一点上下文
     * @param code      代码块全文（前端内存里的当前内容，可能比快照更新）
     * @param startLine 选中片段起始行（1 基）
     * @param endLine   选中片段结束行（1 基）
     * @param quote     选中片段的原文
     * @param question  用户的问题
     */
    public static List<Message> messages(String language, String title, String code,
                                         int startLine, int endLine, String quote, String question) {
        String numbered = withLineNumbers(code, startLine, endLine);

        StringBuilder user = new StringBuilder();
        user.append("代码块：").append(title == null || title.isBlank() ? "未命名" : title);
        if (language != null && !language.isBlank()) {
            user.append("（语言：").append(language).append("）");
        }
        user.append("\n\n```\n").append(numbered).append("\n```\n\n");

        user.append("学生选中的片段（第 ").append(startLine);
        if (endLine != startLine) {
            user.append("~").append(endLine);
        }
        user.append(" 行）：\n```\n").append(quote).append("\n```\n\n");
        user.append("学生的问题：").append(question);

        List<Message> messages = new ArrayList<>(2);
        messages.add(new Message("system", SYSTEM));
        messages.add(new Message("user", user.toString()));
        return messages;
    }

    /**
     * 给代码加行号。
     *
     * <p>自己拼而不是让模型数：模型数行经常差一，而学生对照的是画布上的行号栏。
     * 行号用 {@code 12| } 这种形式，比纯数字更不容易被模型当成代码的一部分。
     */
    static String withLineNumbers(String code, int startLine, int endLine) {
        String[] lines = code.split("\n", -1);

        int from = 0;
        int to = lines.length;
        boolean truncated = false;

        if (code.length() > MAX_CODE_CHARS) {
            int focusStart = Math.max(0, startLine - 1 - WINDOW_LINES);
            int focusEnd = Math.min(lines.length, Math.max(endLine, startLine) + WINDOW_LINES);
            from = focusStart;
            to = focusEnd;
            truncated = true;
        }

        StringBuilder out = new StringBuilder();
        if (truncated && from > 0) {
            out.append("...（前 ").append(from).append(" 行已省略）\n");
        }
        for (int i = from; i < to; i++) {
            out.append(i + 1).append("| ").append(lines[i]);
            if (i < to - 1) {
                out.append('\n');
            }
        }
        if (truncated && to < lines.length) {
            out.append("\n...（后 ").append(lines.length - to).append(" 行已省略）");
        }
        return out.toString();
    }
}
