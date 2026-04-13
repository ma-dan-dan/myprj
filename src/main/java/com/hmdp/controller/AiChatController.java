package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.AiChatRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
@RequestMapping("/ai")
public class AiChatController {

    private static final String SYSTEM_PROMPT = """
            You are the AI customer service assistant for the hm-dianping application.
            Always answer in Simplified Chinese.
            Keep the tone concise, helpful, and service-oriented.
            Prioritize questions about platform features, stores, vouchers, login, and usage guidance.
            Return plain text only and do not use Markdown code blocks.
            """;

    private final ChatClient chatClient;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    public AiChatController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody AiChatRequest request) {
        if (request == null || StrUtil.isBlank(request.getMessage())) {
            throw new IllegalArgumentException("message must not be empty");
        }
        if (StrUtil.isBlank(apiKey)) {
            throw new IllegalStateException("DashScope API key is not configured");
        }

        SseEmitter emitter = new SseEmitter(0L);
        chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(request.getMessage().trim())
                .stream()
                .content()
                .doOnNext(chunk -> send(emitter, "chunk", chunk))
                .doOnComplete(() -> {
                    send(emitter, "done", "[DONE]");
                    emitter.complete();
                })
                .doOnError(ex -> {
                    send(emitter, "error", ex.getMessage());
                    emitter.completeWithError(ex);
                })
                .subscribe();
        return emitter;
    }

    private void send(SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data == null ? "" : data, MediaType.TEXT_PLAIN));
        } catch (IOException ignored) {
            emitter.complete();
        }
    }
}
