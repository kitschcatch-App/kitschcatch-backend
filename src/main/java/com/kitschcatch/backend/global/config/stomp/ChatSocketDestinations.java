// 채팅 STOMP 요청의 정확한 목적지와 양의 채팅방 ID만 허용한다.
package com.kitschcatch.backend.global.config.stomp;

import java.util.regex.Pattern;

final class ChatSocketDestinations {
    private static final Pattern SUBSCRIPTION = Pattern.compile("^/sub/chat-rooms/([1-9][0-9]{0,18})(?:/read)?$");
    private static final Pattern COMMAND = Pattern.compile("^/pub/chat-rooms/([1-9][0-9]{0,18})/(?:messages/text|read)$");

    private ChatSocketDestinations() {}

    static Long subscriptionRoom(String destination) {
        return roomId(SUBSCRIPTION, destination);
    }

    static Long commandRoom(String destination) {
        return roomId(COMMAND, destination);
    }

    private static Long roomId(Pattern pattern, String destination) {
        if (destination == null) return null;
        var matcher = pattern.matcher(destination);
        if (!matcher.matches()) return null;
        try {
            return Long.valueOf(matcher.group(1));
        } catch (NumberFormatException overflow) {
            return null;
        }
    }
}
