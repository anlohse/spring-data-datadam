package com.anlohse.datadam.spring.repository;

import java.lang.reflect.Method;
import java.util.*;

import com.anlohse.datadam.spring.CompoundQuery;
import com.anlohse.datadam.spring.DatadamTemplate;
import com.anlohse.datadam.spring.EntityModel;
import com.anlohse.datadam.spring.SearchQuery;
import com.anlohse.datadam.spring.annotation.DatadamIndex;
import com.anlohse.datadam.spring.annotation.IndexField;
import com.anlohse.datadam.spring.*;
import com.anlohse.datadam.spring.annotation.*;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.data.domain.*;
import org.springframework.data.projection.ProjectionFactory;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.query.*;
import org.springframework.data.repository.query.parser.*;

/** Spring Data PartTree compiler. All filtering and ordering happens on native Datadam indexes. */
final class DerivedDatadamQuery implements RepositoryQuery {
    private static final Set<Part.Type> SUPPORTED = EnumSet.of(Part.Type.SIMPLE_PROPERTY, Part.Type.BETWEEN,
        Part.Type.GREATER_THAN, Part.Type.GREATER_THAN_EQUAL, Part.Type.LESS_THAN, Part.Type.LESS_THAN_EQUAL,
        Part.Type.IN, Part.Type.TRUE, Part.Type.FALSE, Part.Type.STARTING_WITH,
        Part.Type.NEGATING_SIMPLE_PROPERTY, Part.Type.NOT_IN);
    private final Method method;
    private final QueryMethod queryMethod;
    private final PartTree tree;
    private final EntityModel<?> model;
    private final DatadamTemplate template;
    private final List<List<Predicate>> groups;
    private final List<DatadamIndex> indexes;

    DerivedDatadamQuery(Method method, RepositoryMetadata metadata, ProjectionFactory projections, DatadamTemplate template) {
        this.method = method;
        this.template = template;
        queryMethod = new QueryMethod(method, metadata, projections);
        tree = new PartTree(method.getName(), metadata.getDomainType());
        model = new EntityModel<>(metadata.getDomainType());
        indexes = List.of(model.type().getAnnotationsByType(DatadamIndex.class));
        if (tree.isDelete()) throw invalid("Derived deletes are unsupported; use explicit CRUD operations");
        validateReturnType();
        if (queryMethod.getParameters().hasDynamicProjection() || queryMethod.getParameters().hasLimitParameter()
            || queryMethod.getParameters().hasScrollPositionParameter()) throw invalid("Dynamic projection, Limit and scrolling parameters are unsupported");
        if (tree.isLimiting() && (queryMethod.getParameters().hasPageableParameter() || tree.isCountProjection() || tree.isExistsProjection()))
            throw invalid("Top/First cannot be combined with Pageable, count or exists");
        groups = new ArrayList<>();
        int position = 0;
        for (PartTree.OrPart group : tree) {
            List<Predicate> predicates = new ArrayList<>();
            for (Part part : group) {
                if (!SUPPORTED.contains(part.getType())) throw invalid("Unsupported predicate " + part.getType());
                String property = part.getProperty().toDotPath();
                Class<?> type = part.getProperty().getLeafType();
                boolean numeric = isNumeric(type);
                boolean bool = type == boolean.class || type == Boolean.class;
                if (part.getType() == Part.Type.STARTING_WITH && type != String.class)
                    throw invalid("StartingWith requires a String property");
                if (isNegative(part.getType()) && !numeric)
                    throw invalid("Not/NotIn currently require numeric properties; TAG negation cannot safely exclude missing/null fields");
                if (!numeric && !bool && type != String.class && !type.isEnum()) throw invalid("Unsupported scalar property " + property);
                boolean ignoreCase = part.shouldIgnoreCase() != Part.IgnoreCaseType.NEVER && type == String.class;
                if (part.shouldIgnoreCase() == Part.IgnoreCaseType.ALWAYS && type != String.class) throw invalid("IgnoreCase needs a String property");
                if (isRange(part.getType()) && !numeric) throw invalid("Range predicates need a numeric property: " + property);
                if ((part.getType() == Part.Type.TRUE || part.getType() == Part.Type.FALSE) && !bool) throw invalid("True/False needs a boolean property");
                predicates.add(new Predicate(part.getType(), template.indexPath(model, property), position, numeric, ignoreCase));
                position += part.getNumberOfArguments();
            }
            groups.add(List.copyOf(predicates));
        }
        if (position != queryMethod.getParameters().getBindableParameters().getNumberOfParameters())
            throw invalid("Predicate argument count does not match method parameters");
        select(tree.getSort()); // Reject unsupported schema/sort combinations before creating the repository.
    }

    @Override public QueryMethod getQueryMethod() { return queryMethod; }

    @Override public Object execute(Object[] values) {
        var accessor = new ParametersParameterAccessor(queryMethod.getParameters(), values);
        if (accessor.hasBindableNullValue()) throw invalid("Null predicates are unsupported by native indexes");
        Sort requestedSort = combine(tree.getSort(), accessor.getSort());
        Plan plan = select(requestedSort);
        boolean compound = plan.index().kind() == DatadamIndex.Kind.COMPOUND;
        CompoundQuery idx = compound ? compound(plan, accessor) : null;
        SearchQuery ft = compound ? null : search(plan, accessor);
        if (tree.isCountProjection()) return compound ? template.count(plan.index().name(), idx, model)
            : template.searchResults(plan.index().name(), ft, 0, 0, model).total();
        if (tree.isExistsProjection()) return compound ? template.exists(plan.index().name(), idx, model)
            : template.searchResults(plan.index().name(), ft, 0, 0, model).total() > 0;

        Pageable pageable = accessor.getPageable();
        long offset = pageable.isPaged() ? pageable.getOffset() : 0;
        int limit = pageable.isPaged() ? pageable.getPageSize() : tree.isLimiting() ? tree.getMaxResults() : Integer.MAX_VALUE;
        boolean single = method.getReturnType() == Optional.class || method.getReturnType() == model.type();
        if (single) limit = tree.isLimiting() ? tree.getMaxResults() : 2;
        boolean slice = method.getReturnType() == Slice.class;
        int fetch = slice ? Math.addExact(limit, 1) : limit;
        List<?> content;
        long total = 0;
        if (compound) {
            if (single && canLookup(plan.index())) {
                Object[] tuple = Arrays.stream(plan.index().fields()).map(f -> {
                    var predicate = groups.get(0).stream().filter(p -> p.path().equals(f.path())).findFirst().orElseThrow();
                    return scalar(predicate, accessor.getBindableValue(predicate.position()));
                }).toArray();
                content = template.lookup(plan.index().name(), model, tuple);
            } else {
                content = template.queryContent(plan.index().name(), idx, offset, fetch == Integer.MAX_VALUE && pageable.isUnpaged() ? -1 : fetch, model);
            }
            if (queryMethod.isPageQuery()) total = template.count(plan.index().name(), idx, model);
        } else {
            var results = template.searchResults(plan.index().name(), ft, offset, fetch, model);
            content = results.content();
            total = results.total();
        }
        if (single) {
            if (content.size() > 1) throw new IncorrectResultSizeDataAccessException(1, content.size());
            return content.isEmpty() ? null : content.get(0); // Repository proxy wraps Optional results.
        }
        if (queryMethod.isPageQuery()) return new PageImpl<>(content, pageable, total);
        if (slice) return new SliceImpl<>(content.subList(0, Math.min(limit, content.size())), pageable, content.size() > limit);
        return content;
    }

    private Plan select(Sort sort) {
        return select(candidates(sort));
    }
    private Plan select(List<Assessment> candidates) {
        return candidates.stream().map(Assessment::plan).filter(Objects::nonNull)
            .sorted(Comparator.comparingInt(Plan::score).reversed().thenComparing(p -> p.index().name()))
            .findFirst().orElseThrow(() -> invalid("No compatible native index for these predicates and sort; declare a covering IDX index or compatible FT schema; "
                + candidates.stream().map(c -> c.index().name() + ": " + c.reason()).collect(java.util.stream.Collectors.joining("; "))));
    }
    private List<Assessment> candidates(Sort sort) {
        if (sort.stream().anyMatch(o -> o.isIgnoreCase() || o.getNullHandling() != Sort.NullHandling.NATIVE))
            throw invalid("Sort supports native ordering only");
        List<Order> orders = sort.stream().map(o -> new Order(template.indexPath(model, o.getProperty()), o.getDirection())).toList();
        return indexes.stream().map(index -> candidate(index, orders)).toList();
    }

    DerivedQueryPlan explain(Sort dynamicSort) {
        if (dynamicSort.isSorted() && !queryMethod.getParameters().hasSortParameter() && !queryMethod.getParameters().hasPageableParameter())
            throw invalid("Method has no dynamic Sort or Pageable parameter");
        var assessments = candidates(combine(tree.getSort(), dynamicSort));
        var plan = select(assessments);
        boolean compound = plan.index().kind() == DatadamIndex.Kind.COMPOUND;
        boolean single = method.getReturnType() == Optional.class || method.getReturnType() == model.type();
        List<String> commands = !compound ? List.of("FT.SEARCH")
            : tree.isCountProjection() || tree.isExistsProjection() ? List.of("IDX.COUNT")
            : single && canLookup(plan.index()) ? List.of("IDX.GET")
            : queryMethod.isPageQuery() ? List.of("IDX.QUERY", "IDX.COUNT") : List.of("IDX.QUERY");
        var details = assessments.stream().map(c -> new DerivedQueryPlan.Candidate(c.index().name(), c.index().kind(),
            c.plan() != null, c.plan() == null ? 0 : c.plan().score(), c.reason())).toList();
        String reason = assessments.stream().filter(c -> c.plan() == plan).findFirst().orElseThrow().reason()
            + "; highest compatibility score (" + plan.score() + "), index name breaks ties; declared schema only";
        return new DerivedQueryPlan(plan.index().name(), plan.index().kind(), commands, plan.sort(), plan.descending(), reason, details);
    }
    private Assessment reject(DatadamIndex index, String reason) { return new Assessment(index, null, reason); }
    private Assessment candidate(DatadamIndex index, List<Order> orders) {
        List<Predicate> predicates = groups.stream().flatMap(Collection::stream).toList();
        for (Predicate predicate : predicates) {
            var field = field(index, predicate.path());
            if (field == null) return reject(index, "Missing predicate field " + predicate.path());
            if (predicate.numeric() != (field.type() == IndexField.Type.NUMERIC)) return reject(index, "Incompatible field type for " + predicate.path());
            if (!predicate.numeric() && splitTags(index, field)) return reject(index, "Split TAG cannot represent scalar field " + predicate.path());
            if (predicate.ignoreCase() && (index.kind() != DatadamIndex.Kind.SEARCH || field.caseSensitive())) return reject(index, "IgnoreCase requires folded FT TAG " + predicate.path());
            if (!predicate.numeric() && !predicate.ignoreCase() && index.kind() == DatadamIndex.Kind.SEARCH && !field.caseSensitive()) return reject(index, "Exact scalar predicate requires CASESENSITIVE TAG " + predicate.path());
        }
        for (Order order : orders) if (field(index, order.path()) == null) return reject(index, "Missing sort field " + order.path());
        if (orders.stream().anyMatch(o -> field(index, o.path()).type() == IndexField.Type.TAG && splitTags(index, field(index, o.path())))) return reject(index, "Sort requires unsplit TAG");
        if (index.kind() == DatadamIndex.Kind.SEARCH) {
            if (orders.size() > 1) return reject(index, "FT supports one sort field");
            // Sorting strings on a folded TAG changes byte order, even if predicates use other fields.
            if (orders.stream().anyMatch(o -> field(index, o.path()).type() == IndexField.Type.TAG && !field(index, o.path()).caseSensitive())) return reject(index, "TAG sorting requires CASESENSITIVE");
            Sort mappedSort = orders.isEmpty() ? Sort.unsorted() : Sort.by(new Sort.Order(orders.get(0).direction(), field(index, orders.get(0).path()).alias()));
            return new Assessment(index, new Plan(index, false, mappedSort, 1), "Compatible FT schema; native predicates and sort");
        }
        if (groups.size() > 1 || predicates.stream().anyMatch(p -> p.type() == Part.Type.IN || isNegative(p.type()))) return reject(index, "OR, IN and negation require FT");
        // A document missing any IDX field is absent. Every field must therefore be required by predicates.
        if (Arrays.stream(index.fields()).anyMatch(f -> predicates.stream().noneMatch(p -> p.path().equals(f.path())))) return reject(index, "Unconstrained IDX field could lose documents with missing fields");
        Set<String> equalities = new HashSet<>();
        predicates.stream().filter(p -> isEquality(p.type())).forEach(p -> equalities.add(p.path()));
        List<String> varying = Arrays.stream(index.fields()).map(IndexField::path).filter(p -> !equalities.contains(p)).toList();
        List<Order> varyingOrders = orders.stream().filter(o -> !equalities.contains(o.path())).toList();
        if (varyingOrders.size() > varying.size()) return reject(index, "Sort exceeds varying IDX fields");
        for (int i = 0; i < varyingOrders.size(); i++) {
            if (!varyingOrders.get(i).path().equals(varying.get(i)) || varyingOrders.get(i).direction() != varyingOrders.get(0).direction()) return reject(index, "Sort must follow varying IDX tuple fields in one direction");
        }
        int score = 10;
        for (var field : index.fields()) { if (equalities.contains(field.path())) score += 100; else { score += 10; break; } }
        if (canLookup(index)) score += 50;
        boolean descending = !varyingOrders.isEmpty() && varyingOrders.get(0).direction() == Sort.Direction.DESC;
        return new Assessment(index, new Plan(index, descending, Sort.unsorted(), score), canLookup(index)
            ? "Compatible UNIQUE tuple; full equality permits single-result IDX.GET"
            : "Covering IDX predicates; equality prefix and native tuple ordering");
    }

    private CompoundQuery compound(Plan plan, ParametersParameterAccessor accessor) {
        CompoundQuery query = CompoundQuery.all();
        for (Predicate predicate : groups.get(0)) {
            String alias = field(plan.index(), predicate.path()).alias();
            String value = scalar(predicate, predicate.type() == Part.Type.TRUE ? true : predicate.type() == Part.Type.FALSE ? false : accessor.getBindableValue(predicate.position()));
            if (predicate.type() == Part.Type.STARTING_WITH) {
                query = query.where(alias, ">=", value);
                String upper = prefixUpperBound(value);
                if (upper != null) query = query.where(alias, "<", upper);
                continue;
            }
            query = query.where(alias, switch (predicate.type()) {
                case GREATER_THAN -> ">";
                case GREATER_THAN_EQUAL, BETWEEN -> ">=";
                case LESS_THAN -> "<";
                case LESS_THAN_EQUAL -> "<=";
                default -> "=";
            }, value);
            if (predicate.type() == Part.Type.BETWEEN) query = query.where(alias, "<=", scalar(predicate, accessor.getBindableValue(predicate.position() + 1)));
        }
        return plan.descending() ? query.desc() : query;
    }

    private SearchQuery search(Plan plan, ParametersParameterAccessor accessor) {
        Map<String, String> params = new LinkedHashMap<>();
        List<String> disjunctions = new ArrayList<>();
        for (var group : groups) {
            List<String> conjunctions = new ArrayList<>();
            for (Predicate predicate : group) {
                String alias = escape(field(plan.index(), predicate.path()).alias());
                Object value = predicate.type() == Part.Type.TRUE ? true : predicate.type() == Part.Type.FALSE ? false : accessor.getBindableValue(predicate.position());
                if (predicate.type() == Part.Type.IN || predicate.type() == Part.Type.NOT_IN) {
                    List<?> members = members(value);
                    if (members.isEmpty()) throw invalid("IN/NOT_IN needs at least one value");
                    List<String> alternatives = new ArrayList<>();
                    for (Object member : members) alternatives.add(equality(alias, predicate, bind(params, scalar(predicate, member))));
                    String union = "(" + String.join(" | ", alternatives) + ")";
                    // A bare native complement includes documents without this numeric field.
                    conjunctions.add(predicate.type() == Part.Type.NOT_IN ? "(@" + alias + ":[-inf +inf] -" + union + ")" : union);
                } else {
                    String encoded = scalar(predicate, value);
                    if (predicate.type() == Part.Type.STARTING_WITH && (encoded.startsWith(" ") || encoded.startsWith("\t")))
                        throw invalid("FT StartingWith cannot preserve leading spaces/tabs; use an unsplit IDX covering the predicates");
                    String bound = bind(params, (predicate.type() == Part.Type.GREATER_THAN || predicate.type() == Part.Type.LESS_THAN ? "(" : "") + encoded);
                    conjunctions.add(switch (predicate.type()) {
                        case GREATER_THAN, GREATER_THAN_EQUAL -> "@" + alias + ":[" + bound + " +inf]";
                        case LESS_THAN, LESS_THAN_EQUAL -> "@" + alias + ":[-inf " + bound + "]";
                        case BETWEEN -> "@" + alias + ":[" + bound + " " + bind(params, scalar(predicate, accessor.getBindableValue(predicate.position() + 1))) + "]";
                        case STARTING_WITH -> "@" + alias + ":{" + bound + "*}";
                        case NEGATING_SIMPLE_PROPERTY -> "(@" + alias + ":[-inf +inf] -" + equality(alias, predicate, bound) + ")";
                        default -> equality(alias, predicate, bound);
                    });
                }
            }
            disjunctions.add("(" + (conjunctions.isEmpty() ? "*" : String.join(" ", conjunctions)) + ")");
        }
        return new SearchQuery(disjunctions.isEmpty() ? "*" : String.join(" | ", disjunctions), params, plan.sort());
    }

    private String scalar(Predicate predicate, Object value) {
        if (value == null) throw invalid("Null values are unsupported");
        if (predicate.numeric() && (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue())))
            throw invalid("Numeric predicates need finite numbers");
        String encoded = template.scalarValue(value);
        if (predicate.ignoreCase() && encoded.chars().anyMatch(c -> c > 127)) throw invalid("Datadam IgnoreCase supports ASCII only");
        if (!predicate.numeric() && encoded.isEmpty()) throw invalid("Empty TAG predicates are unsupported");
        if (predicate.type() == Part.Type.STARTING_WITH) {
            if (!(value instanceof String) || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(encoded))
                throw invalid("StartingWith requires a valid UTF-8 String prefix");
        }
        return encoded;
    }
    private boolean canLookup(DatadamIndex index) {
        if (!index.unique() || groups.size() != 1 || groups.get(0).size() != index.fields().length) return false;
        return Arrays.stream(index.fields()).allMatch(f -> groups.get(0).stream().filter(p -> p.path().equals(f.path()) && p.type() == Part.Type.SIMPLE_PROPERTY).count() == 1);
    }
    private void validateReturnType() {
        Class<?> type = method.getReturnType();
        if (tree.isCountProjection()) { if (type != long.class && type != Long.class) throw invalid("countBy must return long/Long"); return; }
        if (tree.isExistsProjection()) { if (type != boolean.class && type != Boolean.class) throw invalid("existsBy must return boolean/Boolean"); return; }
        if (type != List.class && type != Page.class && type != Slice.class && type != Optional.class && type != model.type())
            throw invalid("Supported results: entity, Optional, List, Page or Slice");
        if (queryMethod.getReturnedObjectType() != model.type()) throw invalid("Derived projections are unsupported; use @IdxQuery or @FtQuery for native projections");
        if ((type == Optional.class || type == model.type()) && queryMethod.getParameters().hasPageableParameter())
            throw invalid("Single-result methods cannot declare Pageable");
        if (tree.isLimiting() && (type == Optional.class || type == model.type()) && tree.getMaxResults() != 1)
            throw invalid("A single-result method accepts only Top1/First1");
    }
    private static String equality(String alias, Predicate predicate, String bound) {
        return "@" + alias + (predicate.numeric() ? ":[" + bound + " " + bound + "]" : ":{" + bound + "}");
    }
    private static String bind(Map<String, String> parameters, String value) {
        String name = "p" + parameters.size(); parameters.put(name, value); return "$" + name;
    }
    private static IndexField field(DatadamIndex index, String path) {
        return Arrays.stream(index.fields()).filter(f -> f.path().equals(path)).findFirst().orElse(null);
    }
    private static boolean isEquality(Part.Type type) { return type == Part.Type.SIMPLE_PROPERTY || type == Part.Type.TRUE || type == Part.Type.FALSE; }
    private static boolean isNegative(Part.Type type) {
        return type == Part.Type.NEGATING_SIMPLE_PROPERTY || type == Part.Type.NOT_IN;
    }
    /** Unicode scalar order is UTF-8 byte order. Skip surrogate code points and truncate the max suffix. */
    private static String prefixUpperBound(String prefix) {
        for (int end = prefix.length(); end > 0;) {
            int point = prefix.codePointBefore(end);
            end -= Character.charCount(point);
            if (point < Character.MAX_CODE_POINT) {
                int next = point + 1;
                if (next >= Character.MIN_SURROGATE && next <= Character.MAX_SURROGATE) next = Character.MAX_SURROGATE + 1;
                return prefix.substring(0, end) + new String(Character.toChars(next));
            }
        }
        return null;
    }
    private boolean splitTags(DatadamIndex index, IndexField field) {
        return !field.separator().isEmpty() || model.isHash() && index.kind() == DatadamIndex.Kind.SEARCH;
    }
    private static boolean isRange(Part.Type type) {
        return EnumSet.of(Part.Type.BETWEEN, Part.Type.GREATER_THAN, Part.Type.GREATER_THAN_EQUAL, Part.Type.LESS_THAN, Part.Type.LESS_THAN_EQUAL).contains(type);
    }
    private static boolean isNumeric(Class<?> type) {
        return Number.class.isAssignableFrom(type) || Set.of(byte.class, short.class, int.class, long.class, float.class, double.class).contains(type);
    }
    private static String escape(String alias) {
        StringBuilder result = new StringBuilder();
        for (char c : alias.toCharArray()) { if (!Character.isLetterOrDigit(c) && c != '_') result.append('\\'); result.append(c); }
        return result.toString();
    }
    private List<?> members(Object value) {
        if (value instanceof Collection<?> collection) return new ArrayList<>(collection);
        if (value != null && value.getClass().isArray()) {
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) list.add(java.lang.reflect.Array.get(value, i));
            return list;
        }
        throw invalid("IN/NOT_IN needs a Collection or array");
    }
    private Sort combine(Sort fixed, Sort dynamic) {
        Map<String, Sort.Order> orders = new LinkedHashMap<>();
        for (Sort.Order order : fixed.and(dynamic)) {
            var existing = orders.putIfAbsent(order.getProperty(), order);
            if (existing != null && !existing.equals(order)) throw invalid("Conflicting static and dynamic sort");
        }
        return Sort.by(new ArrayList<>(orders.values()));
    }
    private IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message + "; method " + method.toGenericString()); }
    private record Predicate(Part.Type type, String path, int position, boolean numeric, boolean ignoreCase) {}
    private record Order(String path, Sort.Direction direction) {}
    private record Plan(DatadamIndex index, boolean descending, Sort sort, int score) {}
    private record Assessment(DatadamIndex index, Plan plan, String reason) {}
}
