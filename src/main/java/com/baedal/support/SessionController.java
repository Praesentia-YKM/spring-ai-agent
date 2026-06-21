package com.baedal.support;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Round 3 — 세션(대화 메모리) 검증·운영용 엔드포인트.
 * 고객별 세션이 분리 저장되는지, 삭제가 되는지 눈으로 확인하는 용도.
 */
@RestController
@RequestMapping("/api/v1/session")
public class SessionController {

    private final ChatMemory chatMemory;
    private final ChatMemoryRepository chatMemoryRepository;

    public SessionController(ChatMemory chatMemory, ChatMemoryRepository chatMemoryRepository) {
        this.chatMemory = chatMemory;
        this.chatMemoryRepository = chatMemoryRepository;
    }

    /** 해당 세션에 쌓인 메시지 조회 (type + text 만 노출). */
    @GetMapping("/{id}/messages")
    public List<MessageView> messages(@PathVariable String id) {
        return chatMemory.get(id).stream()
                .map(m -> new MessageView(m.getMessageType().name(), m.getText()))
                .toList();
    }

    /** 세션 삭제 — Memory clear. (운영에선 "상담 종료 후 N분" 같은 정책으로 호출) */
    @DeleteMapping("/{id}")
    public void clear(@PathVariable String id) {
        chatMemory.clear(id);
    }

    /** 현재 저장소에 등록된 세션 ID 목록. */
    @GetMapping("/ids")
    public List<String> ids() {
        return chatMemoryRepository.findConversationIds();
    }

    public record MessageView(String type, String text) {}
}
