package cc.taixue.lod.core;
import java.util.*;
import java.util.regex.Pattern;

/** Immutable allow/deny policy; entries match world names, dimension keys or UUIDs. */
public final class WorldPolicy {
    public enum Mode { WHITELIST, BLACKLIST }
    private final Mode mode;
    private final List<Pattern> entries;
    public WorldPolicy(String mode,Collection<String> entries) {
        this.mode=Mode.valueOf(mode.toUpperCase(Locale.ROOT));
        this.entries=entries.stream().map(s->{
            String[] pieces=s.split("\\*",-1);return Pattern.compile(Arrays.stream(pieces).map(Pattern::quote).collect(java.util.stream.Collectors.joining(".*")),Pattern.CASE_INSENSITIVE);
        }).toList();
    }
    public boolean allows(String... identifiers) {
        boolean listed=entries.stream().anyMatch(p->Arrays.stream(identifiers).filter(Objects::nonNull).anyMatch(s->p.matcher(s).matches()));
        return mode==Mode.WHITELIST?listed:!listed;
    }
}
