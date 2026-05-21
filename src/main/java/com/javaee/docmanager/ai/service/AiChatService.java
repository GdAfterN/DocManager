package com.javaee.docmanager.ai.service;

import com.javaee.docmanager.ai.agent.ChatService;
import com.javaee.docmanager.ai.entity.GeneratedFile;
import com.javaee.docmanager.ai.mapper.GeneratedFileMapper;
import com.javaee.docmanager.security.UserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final ChatService chatService;
    private final DocumentGeneratorService documentGeneratorService;
    private final GeneratedFileMapper generatedFileMapper;
    private final MinioClient minioClient;
    private final com.javaee.docmanager.ai.aiops.MonitoringService monitoringService;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String GENERATED_BUCKET = "doc-ai-generated";
    private static final int MAX_PPT_ROUNDS = 5;

    // PPT 多轮对话状态
    private final Map<String, PptConversation> pptConversations = new ConcurrentHashMap<>();

    static class PptConversation {
        final List<Map<String, String>> messages = new ArrayList<>();
        int round = 0;
        int requestedPages = 0;
        String cachedTemplate;
    }

    @Value("${ai.anthropic.api-key}")
    private String apiKey;

    @Value("${ai.anthropic.base-url:https://api.anthropic.com}")
    private String apiBaseUrl;

    @Value("${ai.anthropic.chat.model:claude-sonnet-4-20250514}")
    private String chatModel;

    // ====== 给 LLM 的完整 prompt（布局 + 组件 + 主题 + 动效 + 规则）======

    private static final String LAYOUTS_PROMPT = """
            你必须直接输出完整的HTML<section>代码块，不要输出JSON。每个<section>对应一页PPT，全部section拼在一起就是完整PPT。
            不要用```包裹，不要加任何解释文字，只输出HTML。

            ===== 主题色（必选其一，整个PPT统一用一套）=====
            根据PPT内容选择最合适的主题。在输出中包含一个<style>:root{...}</style>块替换模板默认色。

            🖋 墨水经典（默认，通用安全）:
            --ink:#0a0a0b;--ink-rgb:10,10,11;--paper:#f1efea;--paper-rgb:241,239,234;--paper-tint:#e8e5de;--ink-tint:#18181a;

            🌊 靛蓝瓷（科技/研究/数据）:
            --ink:#0a1f3d;--ink-rgb:10,31,61;--paper:#f1f3f5;--paper-rgb:241,243,245;--paper-tint:#e4e8ec;--ink-tint:#152a4a;

            🌿 森林墨（自然/文化/可持续）:
            --ink:#1a2e1f;--ink-rgb:26,46,31;--paper:#f5f1e8;--paper-rgb:245,241,232;--paper-tint:#ece7da;--ink-tint:#253d2c;

            🍂 牛皮纸（人文/怀旧/阅读）:
            --ink:#2a1e13;--ink-rgb:42,30,19;--paper:#eedfc7;--paper-rgb:238,223,199;--paper-tint:#e0d0b6;--ink-tint:#3a2a1d;

            🌙 沙丘（艺术/设计/时尚）:
            --ink:#1f1a14;--ink-rgb:31,26,20;--paper:#f0e6d2;--paper-rgb:240,230,210;--paper-tint:#e3d7bf;--ink-tint:#2d2620;

            输出方式：在第一个<section>之前插入：
            <style>:root{--ink:#0a0a0b;--ink-rgb:10,10,11;--paper:#f1efea;--paper-rgb:241,239,234;--paper-tint:#e8e5de;--ink-tint:#18181a;}</style>

            ===== 动效系统 =====
            每个需要入场动画的元素加data-anim属性。5种recipe：
            - 默认cascade：不加data-animate，所有data-anim元素逐个stagger淡入（大部分正文页）
            - hero：.hero页自动启用，慢节奏，不需要额外加data-animate
            - quote：<section data-animate="quote">，每行用<span data-anim="line" style="display:block">逐句揭示
            - directional：<section data-animate="directional">，左列data-anim="left"，右列data-anim="right"
            - pipeline：<section data-animate="pipeline">，每个.step加data-anim="step"，按→键逐步点亮

            ===== 可用组件 =====

            -- 标题排版 --
            .h-hero：最大号（10vw），用于hero页主标题，≤5字
            .h-xl：次大号（6-7vw），用于正文页标题
            .h-sub：副标题（3.2vw），用于hero页标题下方
            .h-md：中标题（2vw），用于对比页等子标题
            .lead：引导段（衬线，1.9vw），比正文大的引言段落

            -- 数据卡片（stat-card）：3×2网格 --
            <div class="grid-6" style="margin-top:6vh">
              <div class="stat-card" data-anim>
                <div class="stat-label">Duration</div>
                <div class="stat-nb">64 <span class="stat-unit">天</span></div>
                <div class="stat-note">从0到现在</div>
              </div>
            </div>

            -- Callout 引用框 --
            <div class="callout" data-anim>
              <div class="q-big">"核心引用文字"</div>
              引用解释或补充
              <span class="cite">— 出处</span>
            </div>

            -- 支柱卡（pillar）：三列并列概念 --
            <div style="display:grid;grid-template-columns:repeat(3,1fr);gap:3vw">
              <div class="pillar" data-anim>
                <div class="ic">01</div>
                <div class="t">概念名</div>
                <div class="d">描述文字</div>
              </div>
            </div>

            -- 表格行（rowline）：列表式内容 --
            <div class="rowline" data-anim>
              <div class="k">关键词</div>
              <div class="v">描述文字</div>
              <div class="m">标签</div>
            </div>

            -- 平台卡（plat）：社交/渠道数据 --
            <div class="plat" data-anim>
              <div class="sub">WEIBO</div>
              <div class="name">微博</div>
              <div class="nb">289K</div>
            </div>

            -- Ghost 巨型背景字：装饰性，极低透明度 --
            <div class="ghost" style="right:-6vw;top:-8vh">BUT</div>

            -- Highlight 荧光标记：行内高亮关键词 --
            <span class="hi">关键词</span>

            -- Tag 标签胶囊 --
            <div class="tag">标签文字</div>

            -- 图片占位（无真实图片时用灰色框）--
            <figure class="frame-img r-16x10" data-anim>
              <div style="height:100%;background:var(--paper-tint);display:flex;align-items:center;justify-content:center;opacity:.5">图片</div>
              <figcaption class="img-cap">图片说明</figcaption>
            </figure>
            比例类：.r-16x10 / .r-4x3 / .r-3x2 / .r-3x4 / .r-1x1 / .r-16x9
            固定高：.h-16 / .h-18 / .h-22 / .h-26 / .h-28
            网格内图片必须用固定高度(如height:26vh)，不要用aspect-ratio

            -- Chrome & Foot（每页都应该有）--
            <div class="chrome">
              <div>栏目名 · 分类</div>
              <div>Act I · 02 / 总页</div>
            </div>
            ...
            <div class="foot">
              <div>页脚中文说明</div>
              <div>Act I · English</div>
            </div>

            ===== 10 种布局骨架 =====

            === Layout 1: 开场封面 (hero-cover) ===
            <section class="slide hero dark">
              <div class="chrome">
                <div>A Talk · 2026.04.22</div>
                <div>Vol.01</div>
              </div>
              <div class="frame" style="display:grid;gap:4vh;align-content:center;min-height:80vh">
                <div class="kicker" data-anim>私享会 · 演讲者</div>
                <h1 class="h-hero" data-anim>主标题</h1>
                <h2 class="h-sub" data-anim>副标题</h2>
                <p class="lead" style="max-width:60vw" data-anim>引言段落</p>
                <div class="meta-row" data-anim>
                  <span>作者名</span><span>·</span><span>身份/职位</span>
                </div>
              </div>
              <div class="foot">
                <div>一场关于XX的分享</div>
                <div>— 2026 —</div>
              </div>
            </section>

            === Layout 2: 章节幕封 (act-divider) ===
            <section class="slide hero dark">
              <div class="chrome">
                <div>第一幕 · 主题</div>
                <div>Act I · 01 / 总页</div>
              </div>
              <div class="frame" style="display:grid;gap:6vh;align-content:center;min-height:80vh">
                <div class="kicker" data-anim>Act I</div>
                <h1 class="h-hero" style="font-size:8.5vw" data-anim>章节名</h1>
                <p class="lead" style="max-width:55vw" data-anim>先看XX，再谈XX。</p>
              </div>
              <div class="foot">
                <div>第一幕引子</div>
                <div>— · —</div>
              </div>
            </section>

            === Layout 3: 数据大字报 (big-numbers) ===
            <section class="slide light">
              <div class="chrome">
                <div>过去X天 · 维度</div>
                <div>Act I · 02 / 总页</div>
              </div>
              <div class="frame" style="padding-top:6vh">
                <div class="kicker" data-anim>引导句</div>
                <h2 class="h-xl" data-anim>标题</h2>
                <p class="lead" style="margin-bottom:5vh" data-anim>副标题</p>
                <div class="grid-6" style="margin-top:6vh">
                  <div class="stat-card" data-anim>
                    <div class="stat-label">Label</div>
                    <div class="stat-nb">123 <span class="stat-unit">单位</span></div>
                    <div class="stat-note">说明</div>
                  </div>
                  <div class="stat-card" data-anim>
                    <div class="stat-label">Label2</div>
                    <div class="stat-nb">456</div>
                    <div class="stat-note">说明2</div>
                  </div>
                  <div class="stat-card" data-anim>
                    <div class="stat-label">Label3</div>
                    <div class="stat-nb">789 <span class="stat-unit">%</span></div>
                    <div class="stat-note">说明3</div>
                  </div>
                  <!-- 放3-6个stat-card -->
                </div>
              </div>
              <div class="foot">
                <div>数据来源</div>
                <div>Act I · Numbers</div>
              </div>
            </section>

            === Layout 4: 左文右图 (quote-image) ===
            <section class="slide light">
              <div class="chrome">
                <div>身份反差 · The Twist</div>
                <div>03 / 总页</div>
              </div>
              <div class="frame grid-2-7-5" style="padding-top:6vh">
                <div style="display:flex;flex-direction:column;justify-content:space-between;gap:3vh">
                  <div>
                    <div class="kicker" data-anim>BUT</div>
                    <h2 class="h-xl" data-anim>标题</h2>
                    <p class="lead" style="margin-top:3vh" data-anim>正文段落</p>
                  </div>
                  <div class="callout" data-anim>
                    <div class="q-big">"引用金句"</div>
                    <span class="cite">— 出处</span>
                  </div>
                </div>
                <figure class="frame-img r-16x10" data-anim>
                  <div style="height:100%;background:var(--paper-tint);display:flex;align-items:center;justify-content:center;opacity:.5">图片占位</div>
                  <figcaption class="img-cap">图片说明</figcaption>
                </figure>
              </div>
              <div class="foot">
                <div>页面说明</div>
                <div>— · —</div>
              </div>
            </section>

            === Layout 5: 图片网格 (image-grid) ===
            <section class="slide light">
              <div class="chrome">
                <div>平台粉丝实证</div>
                <div>Act I · 05 / 总页</div>
              </div>
              <div class="frame" style="padding-top:5vh">
                <div class="kicker" data-anim>Proof · 证据</div>
                <h2 class="h-xl" data-anim>标题</h2>
                <div class="grid-3-3" style="margin-top:4vh">
                  <figure class="frame-img h-26" data-anim>
                    <div style="height:100%;background:var(--paper-tint);display:flex;align-items:center;justify-content:center;opacity:.5">截图1</div>
                    <figcaption class="img-cap">说明1</figcaption>
                  </figure>
                  <figure class="frame-img h-26" data-anim>
                    <div style="height:100%;background:var(--paper-tint);display:flex;align-items:center;justify-content:center;opacity:.5">截图2</div>
                    <figcaption class="img-cap">说明2</figcaption>
                  </figure>
                  <!-- 放4-6个同高度frame-img -->
                </div>
              </div>
              <div class="foot">
                <div>截图时间 · 2026.04</div>
                <div>Page 05 · 证据</div>
              </div>
            </section>

            === Layout 6: 流水线 (pipeline) ===
            <section class="slide light" data-animate="pipeline">
              <div class="chrome">
                <div>我的工作流 · Workflow</div>
                <div>Act II · 15 / 总页</div>
              </div>
              <div class="frame">
                <div class="kicker">Pipeline · 流水线</div>
                <h2 class="h-xl">标题</h2>
                <div class="pipeline-section">
                  <div class="pipeline-label">分组名 · Group</div>
                  <div class="pipeline">
                    <div class="step" data-anim="step">
                      <div class="step-nb">01</div>
                      <div class="step-title">步骤名</div>
                      <div class="step-desc">步骤描述</div>
                    </div>
                    <div class="step" data-anim="step">
                      <div class="step-nb">02</div>
                      <div class="step-title">步骤名</div>
                      <div class="step-desc">步骤描述</div>
                    </div>
                    <div class="step" data-anim="step">
                      <div class="step-nb">03</div>
                      <div class="step-title">步骤名</div>
                      <div class="step-desc">步骤描述</div>
                    </div>
                    <!-- 每组3-5个step，可放第二组pipeline-section -->
                  </div>
                </div>
              </div>
              <div class="foot">
                <div>页面说明</div>
                <div>Workflow</div>
              </div>
            </section>

            === Layout 7: 悬念问题 (hero-question) ===
            <section class="slide hero dark">
              <div class="chrome">
                <div>留给你的问题</div>
                <div>24 / 总页</div>
              </div>
              <div class="frame" style="display:grid;gap:8vh;align-content:center;min-height:80vh">
                <div class="kicker" data-anim>The Question</div>
                <h1 class="h-hero" style="font-size:7vw;line-height:1.15" data-anim>你的问题是什么？</h1>
                <p class="lead" style="max-width:50vw" data-anim>这个问题，不是技术问题，是架构问题。</p>
              </div>
              <div class="foot">
                <div>Page 24 · The Question</div>
                <div>— · —</div>
              </div>
            </section>

            === Layout 8: 大引用 (big-quote) ===
            <section class="slide dark" data-animate="quote">
              <div class="chrome">
                <div>The Takeaway · 核心金句</div>
                <div>18 / 总页</div>
              </div>
              <div class="frame" style="display:grid;gap:5vh;align-content:center;min-height:80vh">
                <div class="kicker" data-anim>Quote · 金句</div>
                <blockquote style="font-family:var(--serif-zh);font-weight:700;font-size:5.8vw;line-height:1.2;letter-spacing:-.01em;max-width:72vw">
                  <span data-anim="line" style="display:block">"引用文字第一行，</span>
                  <span data-anim="line" style="display:block">第二行。"</span>
                </blockquote>
                <p class="lead" style="max-width:55vw;opacity:.65" data-anim>
                  英文翻译或解释。
                </p>
                <div class="meta-row" data-anim>
                  <span>— 作者名</span><span>·</span><span>日期</span>
                </div>
              </div>
              <div class="foot">
                <div>Page 18 · 金句</div>
                <div>— · —</div>
              </div>
            </section>

            === Layout 9: 并列对比 (compare) ===
            <section class="slide light" data-animate="directional">
              <div class="chrome">
                <div>旧 vs 新 · The Shift</div>
                <div>12 / 总页</div>
              </div>
              <div class="frame" style="padding-top:5vh">
                <div class="kicker" data-anim>Before / After · 引导</div>
                <h2 class="h-xl" style="margin-bottom:4vh" data-anim>对比标题</h2>
                <div class="grid-2-6-6" style="gap:5vw 4vh">
                  <div data-anim="left" style="padding:3vh 2vw;border-left:3px solid currentColor;opacity:.55">
                    <div class="kicker" style="opacity:.9">Before · 旧模式</div>
                    <h3 class="h-md" style="margin-top:2vh">旧标题</h3>
                    <ul style="margin-top:3vh;padding-left:1.2em;display:flex;flex-direction:column;gap:1.4vh;font-family:var(--sans-zh);font-size:max(14px,1.1vw);line-height:1.55">
                      <li>旧要点1</li><li>旧要点2</li><li>旧要点3</li>
                    </ul>
                  </div>
                  <div data-anim="right" style="padding:3vh 2vw;border-left:3px solid currentColor">
                    <div class="kicker" style="opacity:.9">After · 新模式</div>
                    <h3 class="h-md" style="margin-top:2vh">新标题</h3>
                    <ul style="margin-top:3vh;padding-left:1.2em;display:flex;flex-direction:column;gap:1.4vh;font-family:var(--sans-zh);font-size:max(14px,1.1vw);line-height:1.55">
                      <li>新要点1</li><li>新要点2</li><li>新要点3</li>
                    </ul>
                  </div>
                </div>
              </div>
              <div class="foot">
                <div>Page 12 · 范式转变</div>
                <div>Before / After</div>
              </div>
            </section>

            === Layout 10: 图文混排 (lead-image) ===
            <section class="slide light">
              <div class="chrome">
                <div>Design First · 设计先行</div>
                <div>08 / 总页</div>
              </div>
              <div class="frame grid-2-8-4" style="padding-top:6vh">
                <div>
                  <div class="kicker" data-anim>Phase 01 · 设计阶段</div>
                  <h2 class="h-xl" style="margin-top:1vh;margin-bottom:3vh" data-anim>标题</h2>
                  <p class="lead" style="margin-bottom:3vh" data-anim>引言段落</p>
                  <p data-anim style="font-family:var(--sans-zh);font-size:max(14px,1.15vw);line-height:1.75;opacity:.78;margin-bottom:2.4vh">
                    正文段落，信息量偏大的详细内容。
                  </p>
                  <div class="callout" style="margin-top:3vh" data-anim>
                    <div class="q-big">"引用文字"</div>
                    <span class="cite">— 出处</span>
                  </div>
                </div>
                <figure class="frame-img r-3x4" data-anim>
                  <div style="height:100%;background:var(--paper-tint);display:flex;align-items:center;justify-content:center;opacity:.5">图片占位</div>
                  <figcaption class="img-cap">图片说明</figcaption>
                </figure>
              </div>
              <div class="foot">
                <div>Page 08 · Design First</div>
                <div>约2周</div>
              </div>
            </section>

            ===== 必守规则 =====
            1. 直接输出<section>，不要JSON，不要```包裹，不要解释文字
            2. 每个<section>必须带主题类：light / dark / hero light / hero dark 四选一
            3. 主题节奏硬规则：
               - 不能连续3页以上相同主题
               - 8页以上必须有≥1个hero dark + ≥1个hero light
               - 不能全是light正文页，必须有dark正文页制造呼吸
               - hero-cover用hero dark；act-divider交替hero dark/hero light
               - quote-image/lead-image/big-quote可light/dark交替
               - big-numbers/image-grid/pipeline/compare默认light
            4. 第一页必须是hero-cover，最后一页用hero-question或big-quote收束
            5. chrome和kicker绝对不能写相同内容：
               - chrome = 杂志页眉/栏目标签（跨页可复用，如"Act I · 数据"）
               - kicker = 本页引导句（每页不同，如"BUT"、"一个人，做了什么。"）
            6. 只用上述列出的CSS类名，不要发明新类名
            7. 图片网格内用固定高度(height:26vh)，不要用aspect-ratio
            8. 不要用emoji作图标
            9. 大标题h-hero≤5字，h-xl不超一行
            10. 每个data-anim元素都要加data-anim标记，让动效生效
            11. 全部中文内容（术语可用英文）
            12. 【页数铁律】如果用户要求N页，你的输出必须恰好包含N个<section>标签。逐个计数，不要少也不要多。每个<section>...</section>算一页。
            12. 输出的HTML开头要包含<style>:root{...主题色变量...}</style>
            """;

    public String chat(String message) {
        log.info("AI对话: message length={}", message.length());
        return chatService.callChatApi(message, "ppt.tokens");
    }

    /**
     * 多轮对话式 PPT 生成
     * @return null 表示 AI 还在提问（question 已加入对话历史），非 null 表示已生成文件
     */
    public GeneratedFile chatForPpt(String conversationId, String message) {
        PptConversation conv = pptConversations.computeIfAbsent(conversationId, id -> {
            PptConversation c = new PptConversation();
            c.cachedTemplate = readResource("guizang-ppt-skill-main/assets/template.html");
            return c;
        });

        if (conv.cachedTemplate.isEmpty()) {
            throw new RuntimeException("PPT 模板文件未找到");
        }

        conv.round++;
        log.info("PPT多轮对话: conversationId={}, round={}", conversationId, conv.round);

        // 添加用户消息
        Map<String, String> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", message);
        conv.messages.add(userMsg);

        // 从用户消息中解析页数要求
        int parsedPages = parsePageCount(message);
        if (parsedPages > 0) {
            conv.requestedPages = parsedPages;
            log.info("检测到用户请求页数: {}", parsedPages);
        }

        // 构建 system prompt
        boolean shouldGenerate = conv.round >= MAX_PPT_ROUNDS;

        String pageCountRule = "";
        if (conv.requestedPages > 0) {
            pageCountRule = "用户要求" + conv.requestedPages + "页，必须生成正好" + conv.requestedPages + "个<section>。";
        }

        String systemPrompt = "你是PPT设计顾问。通过交流了解需求后生成PPT。"
                + "对话中简短问关键信息（主题、重点内容、页数），每轮最多问2个问题。"
                + "信息足够或第" + MAX_PPT_ROUNDS + "轮时直接输出PPT的HTML代码。"
                + pageCountRule + "\n\n"
                + LAYOUTS_PROMPT;

        // 调用 LLM（提问轮用 3000，生成轮用 64000 支持最多20页完整 HTML）
        int maxTokens = shouldGenerate ? 64000 : 3000;
        String reply = callLlmWithMessages(systemPrompt, conv.messages, maxTokens);

        // 判断是提问还是生成 HTML sections（必须有≥3个<section>才认为是正式输出，避免把提问中的示例误判）
        int sectionCount = countSections(reply);
        boolean looksLikePpt = sectionCount >= 3
                || (sectionCount >= 1 && shouldGenerate);

        if (looksLikePpt) {
            // 页数校验：如果用户指定了页数但实际 section 不够，重试一次
            if (conv.requestedPages > 0 && sectionCount < conv.requestedPages) {
                log.warn("Section数量不足: 需要{}页，实际{}个，重试生成", conv.requestedPages, sectionCount);
                String retryPrompt = systemPrompt + "\n\n【重要】你上次只生成了" + sectionCount + "个section，用户要求"
                        + conv.requestedPages + "页。必须生成正好" + conv.requestedPages
                        + "个完整的<section>，一个都不能少！";
                reply = callLlmWithMessages(retryPrompt, conv.messages, 64000);
                sectionCount = countSections(reply);
                log.info("重试后section数量: {}", sectionCount);
            }
            GeneratedFile gf = buildPptFromHtml(reply, conv, conv.requestedPages);
            pptConversations.remove(conversationId);
            return gf;
        } else {
            // AI 在提问，保存到对话历史
            Map<String, String> assistantMsg = new LinkedHashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", reply);
            conv.messages.add(assistantMsg);
            return null;
        }
    }

    /** 获取当前对话的提问（chatForPpt 返回 null 后调用） */
    public String getLastPptQuestion(String conversationId) {
        PptConversation conv = pptConversations.get(conversationId);
        if (conv == null || conv.messages.isEmpty()) return "";
        Map<String, String> last = conv.messages.get(conv.messages.size() - 1);
        return "assistant".equals(last.get("role")) ? last.get("content") : "";
    }

    /**
     * 从 LLM 输出的 HTML 中提取 <section> 块，注入模板
     */
    private GeneratedFile buildPptFromHtml(String raw, PptConversation conv, int requestedPages) {
        String sections = extractSections(raw);
        String themeStyle = extractThemeStyle(raw);
        int sectionCount = countSections(raw);
        log.info("提取到 {} 个section, {} 字符, themeStyle={} chars, 请求{}页",
                sectionCount, sections.length(), themeStyle.length(), requestedPages);
        if (requestedPages > 0 && sectionCount < requestedPages) {
            log.warn("页数不满足: 需要{}页，实际只有{}个section", requestedPages, sectionCount);
        }

        // 从第一个 <h1> 或 <h2> 中提取标题作为文件名
        String title = extractTitle(raw);

        return savePptToMinio(title, sections, themeStyle, conv.cachedTemplate);
    }

    /** 从 LLM 输出中提取所有 <section>...</section> 块 */
    private String extractSections(String raw) {
        StringBuilder sb = new StringBuilder();
        // 匹配每个 <section ...> ... </section>
        Pattern p = Pattern.compile("<section[\\s\\S]*?</section>", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(raw);
        while (m.find()) {
            sb.append(m.group()).append("\n");
        }
        // 如果正则没匹配到，尝试把整个输出当作 section 内容
        if (sb.isEmpty() && raw.contains("class=\"slide")) {
            sb.append(raw);
        }
        return sb.toString();
    }

    /** 统计 HTML 中 <section 标签的数量 */
    private int countSections(String html) {
        int count = 0;
        int idx = 0;
        String lower = html.toLowerCase();
        while ((idx = lower.indexOf("<section", idx)) >= 0) {
            count++;
            idx += 8;
        }
        return count;
    }

    /** 从 LLM 输出中提取主题 <style>:root{...} 块 */
    private String extractThemeStyle(String raw) {
        Pattern p = Pattern.compile("<style>\\s*:root\\s*\\{[^}]+\\}\\s*</style>", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(raw);
        if (m.find()) {
            return m.group();
        }
        return "";
    }

    /** 从 HTML 中提取标题（第一个 h1 或 h2 的文本内容） */
    private String extractTitle(String html) {
        Pattern p = Pattern.compile("<h[12][^>]*>(.*?)</h[12]>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(html);
        if (m.find()) {
            String text = m.group(1).replaceAll("<[^>]+>", "").trim();
            if (!text.isEmpty()) return text;
        }
        return "PPT";
    }

    private String callLlmWithMessages(String systemPrompt, List<Map<String, String>> messages, int maxTokens) {
        // 最多重试2次（LLM 偶尔返回空内容）
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String result = doCallLlm(systemPrompt, messages, maxTokens);
                if (!result.isEmpty()) return result;
                log.warn("LLM 返回空内容, attempt={}", attempt);
            } catch (Exception e) {
                log.warn("LLM 调用失败, attempt={}: {}", attempt, e.getMessage());
                if (attempt == 2) throw new RuntimeException("PPT 生成失败: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("PPT 生成失败: LLM 连续返回空内容，请重试");
    }

    private String doCallLlm(String systemPrompt, List<Map<String, String>> messages, int maxTokens) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(300000);
        RestTemplate restTemplate = new RestTemplate(factory);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", chatModel);
        body.put("max_tokens", maxTokens);
        body.put("system", systemPrompt);
        body.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");

        try {
            String requestBody = objectMapper.writeValueAsString(body);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            String url = apiBaseUrl + "/v1/messages";
            log.info("调用 LLM API (多轮): model={}, messages={}条, maxTokens={}", chatModel, messages.size(), maxTokens);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            String responseBody = response.getBody();
            JsonNode root = objectMapper.readTree(responseBody);

            // 记录 token 用量（PPT）
            JsonNode usage = root.path("usage");
            monitoringService.incrementCounter("ppt.tokens.input", usage.path("input_tokens").asInt(0));
            monitoringService.incrementCounter("ppt.tokens.output", usage.path("output_tokens").asInt(0));

            JsonNode contentArray = root.path("content");
            if (contentArray.isArray() && contentArray.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : contentArray) {
                    if ("text".equals(block.path("type").asText())) {
                        sb.append(block.path("text").asText());
                    }
                }
                return sb.toString();
            }

            // 空内容，记录完整响应用于排查
            log.warn("LLM 返回空内容, response={}", responseBody.length() > 500 ? responseBody.substring(0, 500) : responseBody);
            return "";
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public GeneratedFile generateFile(String message, String format) {
        log.info("AI生成文件: format={}", format);
        // 让大模型生成标题和内容
        String prompt = "请根据以下描述生成文档，按以下格式输出：\n"
                + "第一行：文档标题（10个字以内，不加引号）\n"
                + "第二行：空行\n"
                + "第三行起：文档正文内容\n\n"
                + "描述：" + message;
        String raw = chatService.callChatApi(prompt, "ppt.tokens");

        // 解析标题和内容
        String title;
        String content;
        int newlineIdx = raw.indexOf('\n');
        if (newlineIdx > 0 && newlineIdx < 50) {
            title = raw.substring(0, newlineIdx).trim();
            content = raw.substring(newlineIdx).trim();
            // 跳过可能的空行
            if (content.startsWith("\n")) content = content.substring(1);
        } else {
            title = message.length() > 20 ? message.substring(0, 20) : message;
            content = raw;
        }
        // 生成文件名
        String ext = switch (format.toLowerCase()) {
            case "word" -> "docx";
            case "pdf" -> "pdf";
            case "ppt" -> "pptx";
            default -> throw new IllegalArgumentException("不支持的格式: " + format);
        };

        // 生成文件
        byte[] fileBytes = documentGeneratorService.generate(format, title, content);
        String fileId = UUID.randomUUID().toString();
        String objectKey = fileId + "." + ext;
        String fileName = title + "." + ext;

        // 存储到MinIO
        try {
            ensureBucketExists(GENERATED_BUCKET);
            try (var is = new ByteArrayInputStream(fileBytes)) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(GENERATED_BUCKET)
                                .object(objectKey)
                                .stream(is, fileBytes.length, -1)
                                .contentType(getContentType(ext))
                                .build()
                );
            }
            log.info("文件上传到MinIO成功: bucket={}, object={}", GENERATED_BUCKET, objectKey);
        } catch (Exception e) {
            log.error("上传生成文件到MinIO失败", e);
            throw new RuntimeException("文件存储失败: " + e.getMessage(), e);
        }

        // 记录到数据库
        GeneratedFile gf = new GeneratedFile();
        gf.setId(fileId);
        gf.setTitle(fileName);
        gf.setFileFormat(format.toLowerCase());
        gf.setFileId(objectKey);
        gf.setObjectKey(objectKey);
        String username = "system";
        try {
            username = UserContext.getCurrentUsername();
            if (username == null) username = "system";
        } catch (Exception ignored) {}
        gf.setCreateBy(username);
        gf.setCreateTime(new Date());
        generatedFileMapper.insert(gf);

        return gf;
    }

    public GeneratedFile generatePpt(String message) {
        log.info("AI生成PPT: message length={}", message.length());

        String template = readResource("guizang-ppt-skill-main/assets/template.html");
        if (template.isEmpty()) {
            throw new RuntimeException("PPT 模板文件未找到");
        }

        int pageCount = parsePageCount(message);
        String raw = generateHtmlSections(message);
        int sectionCount = countSections(raw);

        // 页数不足时自动重试一次
        if (pageCount > 0 && sectionCount < pageCount) {
            log.warn("Section数量不足: 需要{}页，实际{}个，重试生成", pageCount, sectionCount);
            String retryDesc = message + "\n\n【重要：你必须生成正好" + pageCount
                    + "个<section>，上一次只生成了" + sectionCount + "个。一个都不能少！】";
            raw = generateHtmlSections(retryDesc);
            sectionCount = countSections(raw);
            log.info("重试后section数量: {}", sectionCount);
        }

        String sections = extractSections(raw);
        String themeStyle = extractThemeStyle(raw);
        String title = extractTitle(raw);
        log.info("HTML sections 提取完成: title={}, {}个section, {}字符", title, sectionCount, sections.length());

        return savePptToMinio(title, sections, themeStyle, template);
    }

    private String generateHtmlSections(String userDesc) {
        int pageCount = parsePageCount(userDesc);
        String pageCountRule = pageCount > 0 ? "用户要求" + pageCount + "页，必须生成正好" + pageCount + "个<section>。" : "";

        String prompt = "你是PPT生成助手。请为以下需求生成PPT，直接输出HTML代码。\n\n"
                + "需求：" + userDesc + "\n\n"
                + pageCountRule + "\n\n"
                + LAYOUTS_PROMPT;

        log.info("调用 LLM: prompt={}chars", prompt.length());
        return callLlm(prompt, 64000);
    }

    /** 从文本中解析页数要求，如"10页"、"做一个十页的"、"生成15张" */
    private int parsePageCount(String text) {
        if (text == null) return 0;
        // 匹配 "N页"、"N张"、"N片"
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*[页张片]").matcher(text);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            return Math.max(1, Math.min(n, 20));
        }
        // 匹配中文数字：一~二十
        String[] cnNums = {"一","二","三","四","五","六","七","八","九","十"};
        for (int i = 0; i < cnNums.length; i++) {
            if (text.contains(cnNums[i] + "页") || text.contains(cnNums[i] + "张") || text.contains(cnNums[i] + "片")) {
                return Math.min(i + 1, 20);
            }
        }
        // "十N页"
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("十([一二三四五六七八九])[页张片]").matcher(text);
        if (m2.find()) {
            String digit = m2.group(1);
            for (int i = 0; i < cnNums.length; i++) {
                if (cnNums[i].equals(digit)) return Math.min(10 + i + 1, 20);
            }
        }
        return 0;
    }

    private String readResource(String path) {
        try {
            byte[] bytes;
            try (var is = getClass().getClassLoader().getResourceAsStream(path)) {
                if (is != null) {
                    bytes = is.readAllBytes();
                } else {
                    // 兜底：从文件系统读取（开发环境）
                    java.nio.file.Path filePath = java.nio.file.Path.of(path);
                    if (!java.nio.file.Files.exists(filePath)) {
                        filePath = java.nio.file.Path.of("../" + path);
                    }
                    bytes = java.nio.file.Files.readAllBytes(filePath);
                }
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("读取资源文件失败: {}", path);
            return "";
        }
    }

    private String callLlm(String prompt, int maxTokens) {
        try {
            var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(10000);
            factory.setReadTimeout(300000); // 5 分钟读取超时
            RestTemplate restTemplate = new RestTemplate(factory);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", chatModel);
            body.put("max_tokens", maxTokens);

            Map<String, String> userMessage = new LinkedHashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);
            body.put("messages", Collections.singletonList(userMessage));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            String requestBody = objectMapper.writeValueAsString(body);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            String url = apiBaseUrl + "/v1/messages";
            log.info("调用 LLM API: url={}, model={}, prompt={}chars, maxTokens={}", url, chatModel, prompt.length(), maxTokens);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            log.info("LLM API 响应: status={}", response.getStatusCode());

            JsonNode root = objectMapper.readTree(response.getBody());

            // 记录 token 用量（PPT）
            JsonNode usage = root.path("usage");
            monitoringService.incrementCounter("ppt.tokens.input", usage.path("input_tokens").asInt(0));
            monitoringService.incrementCounter("ppt.tokens.output", usage.path("output_tokens").asInt(0));

            JsonNode contentArray = root.path("content");
            if (contentArray.isArray() && contentArray.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : contentArray) {
                    if ("text".equals(block.path("type").asText())) {
                        sb.append(block.path("text").asText());
                    }
                }
                String result = sb.toString();
                if (!result.isEmpty()) return result;
            }
            throw new RuntimeException("LLM 返回结果为空");
        } catch (Exception e) {
            log.error("调用 LLM 失败", e);
            throw new RuntimeException("PPT 生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 LLM 生成的 HTML sections 注入模板并上传到 MinIO
     */
    private GeneratedFile savePptToMinio(String title, String sectionsHtml, String themeStyle, String template) {
        String html = template.replace("<!-- SLIDES_HERE -->", sectionsHtml);
        html = html.replace("[必填] 替换为 PPT 标题 · Deck Title", title);
        // 注入主题色覆盖（插入到 </head> 之前，覆盖模板默认 :root 变量）
        if (!themeStyle.isEmpty()) {
            int headClose = html.indexOf("</head>");
            if (headClose > 0) {
                html = html.substring(0, headClose) + themeStyle + html.substring(headClose);
            }
        }
        html = injectNavigationOverlay(html);

        String fileId = UUID.randomUUID().toString();
        String htmlKey = fileId + ".html";
        String fileName = title + ".html";

        try {
            ensureBucketExists(GENERATED_BUCKET);

            byte[] htmlBytes = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (var is = new ByteArrayInputStream(htmlBytes)) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(GENERATED_BUCKET)
                                .object(htmlKey)
                                .stream(is, htmlBytes.length, -1)
                                .contentType("text/html; charset=utf-8")
                                .build()
                );
            }
            log.info("HTML 上传成功: object={}", htmlKey);
            monitoringService.incrementCounter("ppt.generated");
        } catch (Exception e) {
            log.error("上传文件到MinIO失败", e);
            throw new RuntimeException("文件存储失败: " + e.getMessage(), e);
        }

        GeneratedFile gf = new GeneratedFile();
        gf.setId(fileId);
        gf.setTitle(fileName);
        gf.setFileFormat("ppt");
        gf.setFileId(htmlKey);
        gf.setObjectKey(htmlKey);
        String username = "system";
        try {
            username = UserContext.getCurrentUsername();
            if (username == null) username = "system";
        } catch (Exception ignored) {}
        gf.setCreateBy(username);
        gf.setCreateTime(new Date());
        generatedFileMapper.insert(gf);

        return gf;
    }

    public List<GeneratedFile> getRecentFiles(int limit) {
        return generatedFileMapper.selectRecent(limit);
    }

    public GeneratedFile getFile(String id) {
        return generatedFileMapper.selectById(id);
    }

    public byte[] downloadFile(String id) {
        GeneratedFile gf = generatedFileMapper.selectById(id);
        if (gf == null) throw new RuntimeException("文件不存在");
        try (var is = minioClient.getObject(
                io.minio.GetObjectArgs.builder()
                        .bucket(GENERATED_BUCKET)
                        .object(gf.getFileId())
                        .build())) {
            return is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("下载失败: " + e.getMessage(), e);
        }
    }

    public byte[] previewFile(String id) {
        GeneratedFile gf = generatedFileMapper.selectById(id);
        if (gf == null) throw new RuntimeException("文件不存在");
        try (var is = minioClient.getObject(
                io.minio.GetObjectArgs.builder()
                        .bucket(GENERATED_BUCKET)
                        .object(gf.getObjectKey())
                        .build())) {
            return is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("预览失败: " + e.getMessage(), e);
        }
    }

    public void deleteFile(String id) {
        GeneratedFile gf = generatedFileMapper.selectById(id);
        if (gf == null) throw new RuntimeException("文件不存在");
        try {
            minioClient.removeObject(
                    io.minio.RemoveObjectArgs.builder()
                            .bucket(GENERATED_BUCKET)
                            .object(gf.getObjectKey())
                            .build()
            );
        } catch (Exception e) {
            log.error("删除MinIO文件失败", e);
        }
        generatedFileMapper.deleteById(id);
    }

    private String injectNavigationOverlay(String html) {
        String navOverlay = """
                <div id="nav-overlay" style="position:fixed;bottom:20px;left:50%;transform:translateX(-50%);z-index:9999;display:flex;align-items:center;gap:16px;background:rgba(0,0,0,0.6);backdrop-filter:blur(8px);padding:10px 20px;border-radius:40px;user-select:none;-webkit-user-select:none;">
                  <button id="nav-prev" style="background:none;border:1px solid rgba(255,255,255,0.4);color:#fff;width:36px;height:36px;border-radius:50%;cursor:pointer;font-size:18px;display:flex;align-items:center;justify-content:center;transition:background .2s;">&#8249;</button>
                  <span id="nav-page" style="color:#fff;font-family:monospace;font-size:14px;min-width:60px;text-align:center;">1 / 1</span>
                  <button id="nav-next" style="background:none;border:1px solid rgba(255,255,255,0.4);color:#fff;width:36px;height:36px;border-radius:50%;cursor:pointer;font-size:18px;display:flex;align-items:center;justify-content:center;transition:background .2s;">&#8250;</button>
                </div>
                <div id="nav-hint" style="position:fixed;bottom:70px;left:50%;transform:translateX(-50%);z-index:9999;color:rgba(255,255,255,0.5);font-size:12px;font-family:monospace;pointer-events:none;opacity:0.8;transition:opacity 1s;">← → 键盘翻页  |  滚轮翻页  |  触屏滑动</div>
                <script>
                (function(){
                  var deck=document.getElementById('deck');
                  if(!deck)return;
                  var slides=deck.querySelectorAll('.slide');
                  var total=slides.length;
                  var cur=0;
                  function getPage(){try{return (typeof idx!=='undefined')?idx:0;}catch(e){return 0;}}
                  function update(){
                    cur=getPage();
                    var el=document.getElementById('nav-page');
                    if(el)el.textContent=(cur+1)+' / '+total;
                  }
                  function goSlide(n){
                    if(n<0||n>=total)return;
                    if(typeof go==='function'){go(n);}
                    else{deck.style.transform='translateX('+(-n*100)+'vw)';}
                    update();
                  }
                  document.getElementById('nav-prev').onclick=function(e){e.stopPropagation();goSlide(Math.max(0,getPage()-1));};
                  document.getElementById('nav-next').onclick=function(e){e.stopPropagation();goSlide(Math.min(total-1,getPage()+1));};
                  setInterval(update,500);
                  update();
                  setTimeout(function(){var h=document.getElementById('nav-hint');if(h)h.style.opacity='0';},5000);
                })();
                </script>
                """;

        int bodyClose = html.lastIndexOf("</body>");
        if (bodyClose > 0) {
            html = html.substring(0, bodyClose) + navOverlay + html.substring(bodyClose);
        }
        return html;
    }

    private void ensureBucketExists(String bucketName) throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
        }
    }

    private String getContentType(String ext) {
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default -> "application/octet-stream";
        };
    }
}
