package com.sender.backend.message;

import com.sender.backend.common.ratelimit.RateLimitPolicy;
import com.sender.backend.common.ratelimit.RateLimited;
import com.sender.backend.message.MessageProgressDtos.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}")
@Tag(name = "Message progress", description = "Delivery, read, and unread indicators")
@SecurityRequirement(name = "bearerAuth")
public class MessageProgressController {
    private final MessageProgressService progress;

    public MessageProgressController(MessageProgressService progress) {
        this.progress = progress;
    }

    @PostMapping("/delivery")
    @RateLimited(RateLimitPolicy.MESSAGE_PROGRESS)
    @Operation(summary = "Acknowledge delivered messages",
            description = "Advances delivery progress monotonically after the client receives a message.")
    @ApiResponse(responseCode = "200", description = "Delivery progress returned")
    @ApiResponse(responseCode = "403", description = "Authenticated user is not a member")
    @ApiResponse(responseCode = "429", description = "Too many requests")
    public ProgressResponse delivered(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer conversationId,
                                      @Valid @RequestBody ProgressRequest request) {
        return progress.acceptDelivered(userId(jwt), conversationId, request);
    }

    @PostMapping("/read")
    @RateLimited(RateLimitPolicy.MESSAGE_PROGRESS)
    @Operation(summary = "Mark conversation messages as read",
            description = "Advances read progress monotonically; read progress also advances delivery.")
    @ApiResponse(responseCode = "200", description = "Read progress returned")
    @ApiResponse(responseCode = "403", description = "Authenticated user is not a member")
    @ApiResponse(responseCode = "429", description = "Too many requests")
    public ProgressResponse read(@AuthenticationPrincipal Jwt jwt, @PathVariable Integer conversationId,
                                 @Valid @RequestBody ProgressRequest request) {
        return progress.acceptRead(userId(jwt), conversationId, request);
    }

    private Integer userId(Jwt jwt) {
        return Integer.valueOf(jwt.getSubject());
    }
}
