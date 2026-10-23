package cc.taixue.lod.core;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class WorldPolicyTest {
    @Test void whitelistIsOptInAndBlacklistIsOptOut() {
        assertFalse(new WorldPolicy("whitelist",List.of()).allows("city"));
        assertTrue(new WorldPolicy("blacklist",List.of()).allows("city"));
        assertTrue(new WorldPolicy("whitelist",List.of("city")).allows("City"));
        assertFalse(new WorldPolicy("blacklist",List.of("secret-*")).allows("secret-copy"));
        assertTrue(new WorldPolicy("blacklist",List.of("secret-*")).allows("public"));
    }
    @Test void exactEntriesAreNotRegularExpressionsAndUuidCanBeUsed() {
        var id=UUID.randomUUID().toString();var p=new WorldPolicy("whitelist",List.of("a.b",id));
        assertFalse(p.allows("axb"));assertTrue(p.allows("new-name",id));
        assertThrows(IllegalArgumentException.class,()->new WorldPolicy("typo",List.of()));
    }
    @Test void playerPreferenceOnlyNarrowsServerPolicy() {
        var server=new WorldPolicy("whitelist",List.of("public"));var player=new WorldPolicy("blacklist",List.of());
        assertFalse(server.allows("secret")&&player.allows("secret"));
    }
}
