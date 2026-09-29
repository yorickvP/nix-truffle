package nixtruffle.fetch;

import nixtruffle.util.Json;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** A flake lock file: a graph of locked inputs ({@code libflake/lockfile.cc}). */
public final class LockFile {
    /**
     * A node: its inputs, each either a {@link Locked} node or a {@code follows} path (a {@code
     * List<String>} of input names from the root). Only the root is a plain node.
     */
    public static class Node {
        public final TreeMap<String, Object> inputs = new TreeMap<>();
    }

    public static final class Locked extends Node {
        public final FlakeRef lockedRef;
        public final FlakeRef originalRef;
        public final boolean isFlake;
        /** The node relative to which a relative {@code path:} input is interpreted. */
        public final List<String> parentPath;

        public Locked(FlakeRef lockedRef, FlakeRef originalRef, boolean isFlake, List<String> parentPath) {
            this.lockedRef = lockedRef;
            this.originalRef = originalRef;
            this.isFlake = isFlake;
            this.parentPath = parentPath;
        }

        Locked(Fetcher f, Map<String, Object> json) {
            lockedRef = getFlakeRef(f, json, "locked", "info");
            originalRef = getFlakeRef(f, json, "original", null);
            isFlake = !Boolean.FALSE.equals(json.get("flake"));
            parentPath = json.containsKey("parent") ? strings(json.get("parent")) : null;
            Input input = lockedRef.input;
            if (!input.isLocked(f) && input.isRelative() == null) {
                if (input.getNarHash() != null) {
                    System.err.println("warning: Lock file entry '" + lockedRef + "' is unlocked (e.g. lacks a Git revision) but is checked by NAR hash. "
                            + "This is not reproducible and will break after garbage collection or when shared.");
                } else {
                    throw new FetchException("Lock file contains unlocked input '" + Attrs.Json.write(input.attrs)
                            + "'. Use '--allow-dirty-locks' to accept this lock file.");
                }
            }
            // For backward compatibility, lock file entries are implicitly final.
            input.attrs.put("__final", true);
        }
    }

    public final Node root = new Node();

    public LockFile() {}

    private static FlakeRef getFlakeRef(Fetcher f, Map<String, Object> json, String attr, String info) {
        Object v = json.get(attr);
        if (v == null) throw new FetchException("attribute '" + attr + "' missing in lock file");
        Attrs attrs = Attrs.Json.fromJson(v);
        if (info != null && json.get(info) != null) attrs.putAll(Attrs.Json.fromJson(json.get(info)));
        return FlakeRef.fromAttrs(f, attrs);
    }

    private static List<String> strings(Object json) {
        List<String> out = new ArrayList<>();
        for (Object o : Json.arr(json)) out.add(Json.str(o));
        return out;
    }

    /** Parses a lock file ({@code path} is for messages). */
    public static LockFile parse(Fetcher f, String contents, String path) {
        Map<String, Object> json;
        try {
            json = Json.obj(Json.parse(contents));
        } catch (IllegalArgumentException e) {
            throw new FetchException("Could not parse '" + path + "': " + e.getMessage());
        }
        Object version = json.getOrDefault("version", 0L);
        long v = version instanceof Long l ? l : 0;
        if (v < 5 || v > 7) throw new FetchException("lock file '" + path + "' has unsupported version " + version);
        LockFile lock = new LockFile();
        String rootKey = Json.str(json.get("root"));
        Map<String, Object> nodes = Json.obj(json.get("nodes"));
        Map<String, Node> nodeMap = new TreeMap<>();
        nodeMap.put(rootKey, lock.root);
        readInputs(f, lock.root, Json.obj(nodes.get(rootKey)), nodes, nodeMap);
        return lock;
    }

    private static void readInputs(Fetcher f, Node node, Map<String, Object> jsonNode, Map<String, Object> nodes, Map<String, Node> nodeMap) {
        Object inputs = jsonNode.get("inputs");
        if (inputs == null) return;
        for (Map.Entry<String, Object> i : Json.obj(inputs).entrySet()) {
            if (i.getValue() instanceof List) {
                node.inputs.put(i.getKey(), strings(i.getValue()));
                continue;
            }
            String inputKey = Json.str(i.getValue());
            Node child = nodeMap.get(inputKey);
            if (child == null) {
                Object jsonNode2 = nodes.get(inputKey);
                if (jsonNode2 == null) throw new FetchException("lock file references missing node '" + inputKey + "'");
                Locked input = new Locked(f, Json.obj(jsonNode2));
                nodeMap.put(inputKey, input);
                readInputs(f, input, Json.obj(jsonNode2), nodes, nodeMap);
                child = input;
            }
            if (!(child instanceof Locked)) throw new FetchException("lock file contains cycle to root node");
            node.inputs.put(i.getKey(), child);
        }
    }

    // ------------------------------------------------------------ printing

    /** The JSON, and the key each node got. */
    public record Dumped(TreeMap<String, Object> json, IdentityHashMap<Node, String> keys) {}

    public Dumped toJSON() {
        TreeMap<String, Object> nodes = new TreeMap<>();
        IdentityHashMap<Node, String> nodeKeys = new IdentityHashMap<>();
        Set<String> keys = new HashSet<>();
        TreeMap<String, Object> json = new TreeMap<>();
        json.put("version", 7L);
        json.put("root", dumpNode("root", root, nodes, nodeKeys, keys));
        json.put("nodes", nodes);
        return new Dumped(json, nodeKeys);
    }

    private static String dumpNode(String key, Node node, TreeMap<String, Object> nodes, IdentityHashMap<Node, String> nodeKeys, Set<String> keys) {
        String k0 = nodeKeys.get(node);
        if (k0 != null) return k0;
        if (!keys.add(key)) {
            for (int n = 2; ; n++) {
                String k = key + "_" + n;
                if (keys.add(k)) {
                    key = k;
                    break;
                }
            }
        }
        nodeKeys.put(node, key);
        TreeMap<String, Object> n = new TreeMap<>();
        if (!node.inputs.isEmpty()) {
            TreeMap<String, Object> inputs = new TreeMap<>();
            for (Map.Entry<String, Object> i : node.inputs.entrySet()) {
                if (i.getValue() instanceof Locked child) {
                    inputs.put(i.getKey(), dumpNode(i.getKey(), child, nodes, nodeKeys, keys));
                } else {
                    inputs.put(i.getKey(), new ArrayList<Object>(followsPath(i.getValue())));
                }
            }
            n.put("inputs", inputs);
        }
        if (node instanceof Locked locked) {
            n.put("original", Attrs.Json.toJson(locked.originalRef.toAttrs()));
            TreeMap<String, Object> l = Attrs.Json.toJson(locked.lockedRef.toAttrs());
            // Lock file entries are always final: the attribute is implied.
            l.remove("__final");
            n.put("locked", l);
            if (!locked.isFlake) n.put("flake", false);
            if (locked.parentPath != null) n.put("parent", new ArrayList<Object>(locked.parentPath));
        }
        nodes.put(key, n);
        return key;
    }

    @SuppressWarnings("unchecked")
    public static List<String> followsPath(Object edge) {
        return (List<String>) edge;
    }

    @Override
    public String toString() {
        return Json.write(toJSON().json(), 2);
    }

    /** Lock files are equal if their JSON is. */
    public boolean sameAs(LockFile other) {
        return toString().equals(other.toString());
    }

    // ------------------------------------------------------------- queries

    /** An unlocked or non-final input (relative ones are fine), or null. */
    public FlakeRef isUnlocked(Fetcher f) {
        Set<Node> nodes = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        visit(root, nodes);
        for (Node n : nodes) {
            if (n instanceof Locked node) {
                Input input = node.lockedRef.input;
                if ((!input.isLocked(f) || !input.isFinal()) && input.isRelative() == null) return node.lockedRef;
            }
        }
        return null;
    }

    private static void visit(Node node, Set<Node> seen) {
        if (!seen.add(node)) return;
        for (Object e : node.inputs.values()) if (e instanceof Locked child) visit(child, seen);
    }

    /** The node an input path leads to (following {@code follows}), or null. */
    public Node findInput(List<String> path) {
        return doFind(path, new ArrayList<>());
    }

    private Node doFind(List<String> path, List<List<String>> visited) {
        int found = visited.indexOf(path);
        if (found >= 0) {
            List<String> cycle = new ArrayList<>();
            for (List<String> p : visited.subList(found, visited.size())) cycle.add(String.join("/", p));
            cycle.add(String.join("/", path));
            throw new FetchException("follow cycle detected: [" + String.join(" -> ", cycle) + "]");
        }
        visited.add(path);
        Node pos = root;
        for (String elem : path) {
            Object edge = pos.inputs.get(elem);
            if (edge == null) return null;
            if (edge instanceof Locked child) {
                pos = child;
            } else {
                Node p = doFind(followsPath(edge), visited);
                if (p == null) return null;
                pos = p;
            }
        }
        return pos;
    }

    /** Every input path reachable from the root, with its edge. */
    public TreeMap<List<String>, Object> getAllInputs() {
        TreeMap<List<String>, Object> res = new TreeMap<>(LockFile::comparePaths);
        Set<Node> done = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        allInputs(new ArrayList<>(), root, done, res);
        return res;
    }

    private static void allInputs(List<String> prefix, Node node, Set<Node> done, TreeMap<List<String>, Object> res) {
        if (!done.add(node)) return;
        for (Map.Entry<String, Object> e : node.inputs.entrySet()) {
            List<String> path = new ArrayList<>(prefix);
            path.add(e.getKey());
            res.putIfAbsent(path, e.getValue());
            if (e.getValue() instanceof Locked child) allInputs(path, child, done, res);
        }
    }

    public static int comparePaths(List<String> a, List<String> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = a.get(i).compareTo(b.get(i));
            if (c != 0) return c;
        }
        return Integer.compare(a.size(), b.size());
    }

    /** Every {@code follows} must lead somewhere. */
    public void check() {
        for (Map.Entry<List<String>, Object> e : getAllInputs().entrySet()) {
            if (e.getValue() instanceof List<?>) {
                List<String> follows = followsPath(e.getValue());
                if (!follows.isEmpty() && findInput(follows) == null) {
                    throw new FetchException("input '" + String.join("/", e.getKey()) + "' follows a non-existent input '" + String.join("/", follows) + "'");
                }
            }
        }
    }

    /** A summary of the changes, like {@code LockFile::diff}. */
    public static String diff(LockFile oldLocks, LockFile newLocks) {
        var oldFlat = new ArrayList<>(oldLocks.getAllInputs().entrySet());
        var newFlat = new ArrayList<>(newLocks.getAllInputs().entrySet());
        StringBuilder res = new StringBuilder();
        int i = 0, j = 0;
        while (i < oldFlat.size() || j < newFlat.size()) {
            if (j < newFlat.size() && (i == oldFlat.size() || comparePaths(oldFlat.get(i).getKey(), newFlat.get(j).getKey()) > 0)) {
                res.append("• Added input '").append(String.join("/", newFlat.get(j).getKey())).append("':\n    ")
                        .append(describeEdge(newFlat.get(j).getValue())).append('\n');
                j++;
            } else if (i < oldFlat.size() && (j == newFlat.size() || comparePaths(oldFlat.get(i).getKey(), newFlat.get(j).getKey()) < 0)) {
                res.append("• Removed input '").append(String.join("/", oldFlat.get(i).getKey())).append("'\n");
                i++;
            } else {
                Object e1 = oldFlat.get(i).getValue(), e2 = newFlat.get(j).getValue();
                if (!edgesEqual(e1, e2)) {
                    res.append("• Updated input '").append(String.join("/", oldFlat.get(i).getKey())).append("':\n    ")
                            .append(describeEdge(e1)).append("\n  → ").append(describeEdge(e2)).append('\n');
                }
                i++;
                j++;
            }
        }
        return res.toString();
    }

    private static boolean edgesEqual(Object e1, Object e2) {
        if (e1 instanceof Locked n1 && e2 instanceof Locked n2) return n1.lockedRef.sameAs(n2.lockedRef);
        if (e1 instanceof List<?> f1 && e2 instanceof List<?> f2) return f1.equals(f2);
        return false;
    }

    private static String describeEdge(Object edge) {
        if (edge instanceof Locked n) {
            String s = "'" + n.lockedRef + "'";
            Long lastModified = n.lockedRef.input.getLastModified();
            if (lastModified != null) {
                s += " (" + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(java.time.ZoneOffset.UTC)
                        .format(java.time.Instant.ofEpochSecond(lastModified)) + ")";
            }
            return s;
        }
        return "follows '" + String.join("/", followsPath(edge)) + "'";
    }

    private static final Pattern FLAKE_ID = Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]*");

    /** {@code parseInputAttrPath}: {@code a/b/c}, every element a flake id. */
    public static List<String> parseInputAttrPath(String s) {
        List<String> path = new ArrayList<>();
        for (String elem : s.split("/")) {
            if (elem.isEmpty()) continue;
            if (!FLAKE_ID.matcher(elem).matches()) throw new FetchException("invalid flake input attribute path element '" + elem + "'");
            path.add(elem);
        }
        return path;
    }
}
