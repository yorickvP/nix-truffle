package nixtruffle.fetch;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** {@code indirect} inputs ({@code flake:nixpkgs}): names to look up in the flake registries. */
final class IndirectScheme implements InputScheme {
    static final Pattern FLAKE_ID = Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]*");

    @Override
    public String name() {
        return "indirect";
    }

    @Override
    public boolean needsFlakes() {
        return true;
    }

    @Override
    public Input inputFromURL(Fetcher f, Url url, boolean requireTree) {
        if (!url.scheme.equals("flake")) return null;
        List<String> path = url.pathSegments(true);
        String rev = null;
        String ref = null;
        if (path.size() == 1) {
            // just an id
        } else if (path.size() == 2) {
            if (GitArchiveScheme.REV.matcher(path.get(1)).matches()) {
                rev = path.get(1).toLowerCase();
            } else if (RefNames.isLegal(path.get(1))) {
                ref = path.get(1);
            } else {
                throw new FetchException.BadUrl("in flake URL '" + url + "', '" + path.get(1) + "' is not a commit hash or branch/tag name");
            }
        } else if (path.size() == 3) {
            if (!RefNames.isLegal(path.get(1))) throw new FetchException.BadUrl("in flake URL '" + url + "', '" + path.get(1) + "' is not a branch/tag name");
            ref = path.get(1);
            if (!GitArchiveScheme.REV.matcher(path.get(2)).matches()) {
                throw new FetchException.BadUrl("in flake URL '" + url + "', '" + path.get(2) + "' is not a commit hash");
            }
            rev = path.get(2).toLowerCase();
        } else {
            throw new FetchException.BadUrl("GitHub URL '" + url + "' is invalid");
        }
        String id = path.isEmpty() ? "" : path.get(0);
        if (!FLAKE_ID.matcher(id).matches()) throw new FetchException.BadUrl("'" + id + "' is not a valid flake ID");
        Attrs attrs = Attrs.of("type", "indirect", "id", id);
        if (rev != null) attrs.put("rev", rev);
        if (ref != null) attrs.put("ref", ref);
        return new Input(attrs, this);
    }

    @Override
    public Set<String> allowedAttrs() {
        return Set.of("id", "ref", "rev", "narHash");
    }

    @Override
    public Input inputFromAttrs(Fetcher f, Attrs attrs) {
        String id = attrs.requireStr("id");
        if (!FLAKE_ID.matcher(id).matches()) throw new FetchException.BadUrl("'" + id + "' is not a valid flake ID");
        return new Input(attrs, this);
    }

    @Override
    public Url toURL(Input input) {
        List<String> path = new ArrayList<>(List.of(input.attrs.requireStr("id")));
        String ref = input.getRef();
        if (ref != null) path.add(ref);
        String rev = input.getRev();
        if (rev != null) path.add(rev);
        return new Url("flake", null, path, null, null);
    }

    @Override
    public Input applyOverrides(Input input, String ref, String rev) {
        Attrs a = input.attrs.copy();
        if (rev != null) a.put("rev", rev);
        if (ref != null) a.put("ref", ref);
        return input.withAttrs(a);
    }

    @Override
    public boolean isLocked(Fetcher f, Input input) {
        return false;
    }

    @Override
    public boolean isDirect(Input input) {
        return false;
    }

    @Override
    public Result fetch(Fetcher f, Input input) {
        throw new FetchException("indirect input '" + input + "' cannot be fetched directly");
    }
}
