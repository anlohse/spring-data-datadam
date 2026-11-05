package io.datadam.spring;

/** Parses only singular member/index paths; arbitrary selectors remain available for reads. */
final class JsonMutationPath {
    static String firstMember(String path) {
        int i = path.startsWith("$") ? 1 : 0;
        String first = null;
        while (i < path.length()) {
            if (path.charAt(i) == '.') { i++; if (i == path.length()) throw invalid(); }
            String member;
            if (path.charAt(i) == '[') {
                i++; if (i == path.length()) throw invalid();
                char quote = path.charAt(i);
                if (quote == '\'' || quote == '"') {
                    i++; var name = new StringBuilder(); boolean closed = false;
                    while (i < path.length()) {
                        char c = path.charAt(i++);
                        if (c == quote) { closed = true; break; }
                        if (c == '\\') { if (i == path.length()) throw invalid(); c = path.charAt(i++); }
                        name.append(c);
                    }
                    if (!closed || i == path.length() || path.charAt(i++) != ']') throw invalid();
                    member = name.toString();
                } else {
                    if (quote == '-') i++;
                    int start = i;
                    while (i < path.length() && Character.isDigit(path.charAt(i))) i++;
                    if (i == start || i == path.length() || path.charAt(i++) != ']') throw invalid();
                    member = null;
                }
            } else {
                int start = i;
                while (i < path.length() && path.charAt(i) != '.' && path.charAt(i) != '[') {
                    char c = path.charAt(i++);
                    if (c == ']' || c == '*' || c == '$' || c == '\\' || Character.isWhitespace(c)) throw invalid();
                }
                if (i == start) throw invalid();
                member = path.substring(start, i);
            }
            if (first == null) { if (member == null) throw invalid(); first = member; }
            if (i < path.length() && path.charAt(i) != '.' && path.charAt(i) != '[') throw invalid();
        }
        if (first == null) throw invalid();
        return first;
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Partial writes require a non-root singular member/index path; wildcards, filters, unions and descendants are read-only");
    }
}
