package org.openelisglobal.spring;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.beans.Introspector;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Boot-order tripwire for the LIS-243 landmine class: an eager injection cycle
 * that passes through an {@code @Async} bean.
 *
 * <p>
 * {@code @Async} proxies are applied by
 * {@code AsyncAnnotationBeanPostProcessor} AFTER bean creation and, unlike the
 * transactional auto-proxy creator, that post-processor cannot expose an early
 * proxy reference. If singleton creation enters such a cycle at the async bean,
 * another bean in the cycle receives the raw (unwrapped) instance and Spring
 * aborts startup with "injected into other beans ... in its raw version as part
 * of a circular reference, but has eventually been wrapped". Whether that
 * happens depends only on bean creation ORDER, which follows classpath-scan
 * order — i.e. boot survives or dies by bean-name sorting (this bit production
 * in LIS-226/LIS-242: an eager FhirTransformService injection in a bean sorting
 * before fhirReferralServiceImpl made the context unbootable).
 *
 * <p>
 * No core CI boots the production bean graph (test contexts mock the FHIR
 * services; the umbrella Stage-4 clean-box smoke is the only real boot gate),
 * so this test closes the gap statically: it scans every component-annotated
 * class under {@code org.openelisglobal}, builds the eager injection graph
 * (field, setter and constructor injection points, skipping {@code @Lazy}
 * ones), and fails on any cycle that contains a bean declaring {@code @Async}.
 *
 * <p>
 * Modeled subset: {@code @Autowired} field/setter/constructor injection into
 * component-scanned classes under {@code org.openelisglobal}. NOT modeled:
 * {@code @Bean} factory methods, {@code @Resource}/{@code @Inject} injection
 * points, XML-defined beans, {@code Optional<T>} wrappers, and beans outside
 * the base package (all verified absent from the graph at the time of writing)
 * — the Stage-4 clean-box smoke remains the authority for the fully assembled
 * production graph.
 *
 * <p>
 * If this test fails, break the cycle: prefer removing the offending
 * dependency, otherwise mark ONE edge of the cycle {@code @Lazy} (see
 * {@code FhirReferralServiceImpl.fhirTransformService} for the precedent).
 */
public class EagerAsyncInjectionCycleTest {

    private static final String BASE_PACKAGE = "org.openelisglobal";

    /** Injected types that never force eager creation of a target bean. */
    private static final Set<String> NON_BEAN_TYPES = new HashSet<>(List.of(
            "org.springframework.beans.factory.ObjectFactory", "org.springframework.beans.factory.ObjectProvider",
            "jakarta.inject.Provider", "javax.inject.Provider", "org.springframework.context.ApplicationContext",
            "org.springframework.beans.factory.BeanFactory"));

    @Test
    public void eagerInjectionGraph_hasNoCycleThroughAsyncBeans() {
        List<Class<?>> beans = scanComponentClasses();
        assertTrue("classpath scan found suspiciously few beans (" + beans.size() + ") — scanner misconfigured?",
                beans.size() > 100);

        Map<Class<?>, Map<Class<?>, String>> graph = buildEagerInjectionGraph(beans);

        List<String> violations = new ArrayList<>();
        for (Class<?> bean : beans) {
            if (declaresAsync(bean)) {
                List<String> cycle = findCycleThrough(bean, graph);
                if (cycle != null) {
                    violations.add(String.join("\n      -> ", cycle));
                }
            }
        }
        if (!violations.isEmpty()) {
            fail("Eager injection cycle(s) through @Async bean(s) — production boot now depends on bean creation"
                    + " order and WILL fail when singleton creation enters the cycle at the async bean"
                    + " (see LIS-226/LIS-242/LIS-243). Break the dependency or mark one edge @Lazy:\n   "
                    + String.join("\n   ", violations));
        }
    }

    /**
     * Companion tripwire: the specific edge fixed under LIS-243. Kept alongside the
     * graph test so an upstream-sync merge that silently drops the @Lazy is caught
     * with a precise message even if the graph test is ever weakened.
     */
    @Test
    public void fhirReferralService_fhirTransformServiceInjection_mustStayLazy() throws Exception {
        Field field = org.openelisglobal.referral.fhir.service.FhirReferralServiceImpl.class
                .getDeclaredField("fhirTransformService");
        assertTrue("FhirReferralServiceImpl.fhirTransformService must be injected @Lazy — it is the cycle-breaking"
                + " edge of the fhirTransform -> referralSet -> fhirReferral -> fhirTransform circular reference;"
                + " removing it makes production context boot depend on bean-name sort order (LIS-243)",
                field.isAnnotationPresent(Lazy.class));
    }

    // ------------------------------------------------------------------
    // graph construction
    // ------------------------------------------------------------------

    private List<Class<?>> scanComponentClasses() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(BASE_PACKAGE)) {
            try {
                classes.add(Class.forName(bd.getBeanClassName(), false, getClass().getClassLoader()));
            } catch (ClassNotFoundException | LinkageError e) {
                // Not loadable in the test JVM (optional/plugin dependency) — it
                // cannot participate in this static check; the Stage-4 smoke
                // remains the authority for the fully assembled graph.
            }
        }
        return classes;
    }

    /** bean class -> (target bean class -> description of the injection point) */
    private Map<Class<?>, Map<Class<?>, String>> buildEagerInjectionGraph(List<Class<?>> beans) {
        Map<Class<?>, Map<Class<?>, String>> graph = new LinkedHashMap<>();
        for (Class<?> bean : beans) {
            Map<Class<?>, String> edges = new LinkedHashMap<>();
            for (Class<?> c = bean; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (field.isAnnotationPresent(Autowired.class) && !field.isAnnotationPresent(Lazy.class)) {
                        addEdges(edges, beans, ResolvableType.forField(field), qualifierOf(field),
                                c.getSimpleName() + "." + field.getName());
                    }
                }
                for (Method method : c.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(Autowired.class) && !method.isAnnotationPresent(Lazy.class)) {
                        addParameterEdges(edges, beans, method, c.getSimpleName() + "." + method.getName() + "(..)");
                    }
                }
            }
            Constructor<?> injectionCtor = injectionConstructor(bean);
            if (injectionCtor != null && !injectionCtor.isAnnotationPresent(Lazy.class)) {
                addParameterEdges(edges, beans, injectionCtor, bean.getSimpleName() + ".<init>(..)");
            }
            graph.put(bean, edges);
        }
        return graph;
    }

    private Constructor<?> injectionConstructor(Class<?> bean) {
        Constructor<?>[] ctors = bean.getDeclaredConstructors();
        for (Constructor<?> ctor : ctors) {
            if (ctor.isAnnotationPresent(Autowired.class)) {
                return ctor;
            }
        }
        // Spring's implicit single-constructor injection
        if (ctors.length == 1 && ctors[0].getParameterCount() > 0) {
            return ctors[0];
        }
        return null;
    }

    private void addParameterEdges(Map<Class<?>, String> edges, List<Class<?>> beans, Executable executable,
            String where) {
        Parameter[] parameters = executable.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].isAnnotationPresent(Lazy.class)) {
                continue;
            }
            addEdges(edges, beans, ResolvableType.forType(parameters[i].getParameterizedType()),
                    qualifierOf(parameters[i]), where);
        }
    }

    private void addEdges(Map<Class<?>, String> edges, List<Class<?>> beans, ResolvableType declared, String qualifier,
            String where) {
        ResolvableType targetType = unwrapContainer(declared);
        Class<?> raw = targetType.resolve();
        if (raw == null || raw.isPrimitive() || NON_BEAN_TYPES.contains(raw.getName())
                || !raw.getName().startsWith(BASE_PACKAGE)) {
            return;
        }
        List<Class<?>> candidates = new ArrayList<>();
        for (Class<?> bean : beans) {
            if (raw.isAssignableFrom(bean)) {
                candidates.add(bean);
            }
        }
        if (qualifier != null) {
            List<Class<?>> named = new ArrayList<>();
            for (Class<?> candidate : candidates) {
                if (qualifier.equals(defaultBeanName(candidate))) {
                    named.add(candidate);
                }
            }
            if (!named.isEmpty()) {
                candidates = named; // fall back to all assignables when unresolvable — conservative
            }
        }
        for (Class<?> candidate : candidates) {
            edges.putIfAbsent(candidate, where);
        }
    }

    /** Collection/Map injection points eagerly create every element bean. */
    private ResolvableType unwrapContainer(ResolvableType type) {
        Class<?> raw = type.resolve();
        if (raw != null && Collection.class.isAssignableFrom(raw)) {
            return type.asCollection().getGeneric(0);
        }
        if (raw != null && Map.class.isAssignableFrom(raw)) {
            return type.asMap().getGeneric(1);
        }
        return type;
    }

    private String qualifierOf(AnnotatedElement element) {
        Qualifier qualifier = element.getAnnotation(Qualifier.class);
        return qualifier != null && !qualifier.value().isEmpty() ? qualifier.value() : null;
    }

    private String defaultBeanName(Class<?> bean) {
        for (Annotation annotation : bean.getAnnotations()) {
            String value = componentValue(annotation);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return Introspector.decapitalize(bean.getSimpleName());
    }

    private String componentValue(Annotation annotation) {
        Component component = AnnotatedElementUtils.findMergedAnnotation(annotation.annotationType(), Component.class);
        if (component == null && !(annotation instanceof Component)) {
            return null;
        }
        try {
            Method valueMethod = annotation.annotationType().getMethod("value");
            Object value = valueMethod.invoke(annotation);
            return value instanceof String ? (String) value : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // cycle detection
    // ------------------------------------------------------------------

    private boolean declaresAsync(Class<?> bean) {
        // Spring's async advisor matches the target class AND its interfaces
        // (AopUtils.canApply), so an @Async declared only on an implemented
        // interface still gets the bean proxied — walk both hierarchies.
        Set<Class<?>> types = new HashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        queue.add(bean);
        while (!queue.isEmpty()) {
            Class<?> c = queue.poll();
            if (c == Object.class || !types.add(c)) {
                continue;
            }
            if (c.getSuperclass() != null) {
                queue.add(c.getSuperclass());
            }
            queue.addAll(List.of(c.getInterfaces()));
        }
        for (Class<?> c : types) {
            if (c.isAnnotationPresent(Async.class)) {
                return true;
            }
            for (Method method : c.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Async.class)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * BFS from {@code start}'s successors back to {@code start}; returns the
     * labelled cycle path, or null when no eager cycle passes through it.
     */
    private List<String> findCycleThrough(Class<?> start, Map<Class<?>, Map<Class<?>, String>> graph) {
        Map<Class<?>, Class<?>> parent = new HashMap<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        for (Class<?> next : graph.getOrDefault(start, Map.of()).keySet()) {
            if (!parent.containsKey(next)) {
                parent.put(next, start);
                queue.add(next);
            }
        }
        while (!queue.isEmpty()) {
            Class<?> current = queue.poll();
            if (current == start) {
                return reconstruct(start, parent, graph);
            }
            for (Class<?> next : graph.getOrDefault(current, Map.of()).keySet()) {
                if (!parent.containsKey(next)) {
                    parent.put(next, current);
                    queue.add(next);
                }
            }
        }
        return null;
    }

    private List<String> reconstruct(Class<?> start, Map<Class<?>, Class<?>> parent,
            Map<Class<?>, Map<Class<?>, String>> graph) {
        List<Class<?>> nodes = new ArrayList<>();
        nodes.add(start);
        for (Class<?> node = parent.get(start); node != start; node = parent.get(node)) {
            nodes.add(node);
        }
        nodes.add(start);
        java.util.Collections.reverse(nodes);
        List<String> labelled = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            Class<?> node = nodes.get(i);
            String label = node.getSimpleName();
            if (i < nodes.size() - 1) {
                label += " [" + graph.get(node).get(nodes.get(i + 1)) + "]";
            }
            labelled.add(label);
        }
        return labelled;
    }
}
