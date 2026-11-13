package dev.holoweb.troupe;

import com.google.gson.JsonParser;
import dev.holoweb.troupe.net.Protocol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The ping's troupe object comes from any server: only well-formed info gets a badge. */
class TroupeStatusTest {
    private static TroupeStatus.Info parse(String json) {
        return TroupeStatus.parse(JsonParser.parseString(json));
    }

    @Test
    void readsWhatServersSend() {
        TroupeStatus.Info i = parse("{\"protocol\":" + Protocol.VERSION + ",\"resources\":2,\"size\":2048,\"names\":[\"base\",\"exemplo\"]}");
        assertEquals(new TroupeStatus.Info(Protocol.VERSION, Protocol.VERSION, 2, 2048, List.of("base", "exemplo")), i);
        assertTrue(i.compatible());
        assertFalse(parse("{\"protocol\":1,\"resources\":1,\"size\":1}").compatible());
        // a newer server that still speaks our version is fine; one that dropped it isn't
        int next = Protocol.VERSION + 1;
        assertTrue(parse("{\"protocol\":" + next + ",\"min\":" + Protocol.VERSION + ",\"resources\":1,\"size\":1}").compatible());
        assertFalse(parse("{\"protocol\":" + next + ",\"min\":" + next + ",\"resources\":1,\"size\":1}").compatible());
    }

    @Test
    void dropsJunk() {
        assertNull(parse("\"troupe\""));
        assertNull(parse("{\"resources\":1}"));
        assertNull(parse("{\"protocol\":3,\"resources\":-1,\"size\":0}"));
        StringBuilder many = new StringBuilder("[");
        for (int n = 0; n < 50; n++) many.append(n == 0 ? "" : ",").append("\"r").append(n).append('"');
        TroupeStatus.Info i = parse("{\"protocol\":3,\"resources\":50,\"size\":0,\"names\":" + many + ",\"Bad Name\"]}");
        assertEquals(20, i.names().size(), "names are capped");
        assertEquals(List.of("ok"), parse("{\"protocol\":3,\"resources\":2,\"size\":0,\"names\":[\"ok\",\"§cRed\",{\"x\":1}]}").names());
    }
}
