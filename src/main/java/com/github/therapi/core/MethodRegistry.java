package com.github.therapi.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.github.therapi.core.internal.MethodDefinition;
import com.github.therapi.core.internal.ParameterDefinition;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.therapi.core.internal.JacksonHelper.isLikeNull;
import static com.google.common.base.Throwables.propagate;
import static java.util.Collections.unmodifiableCollection;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.apache.commons.lang3.StringUtils.getLevenshteinDistance;

public class MethodRegistry {
    private static final Logger log = LoggerFactory.getLogger(MethodRegistry.class);

    private final HashMap<String, MethodDefinition> methodsByName = new HashMap<>();

    private MethodIntrospector scanner;
    private final ObjectMapper objectMapper;
    private String namespaceSeparator = ".";

    private boolean suggestMethods = true;

    public boolean isSuggestMethods() {
        return suggestMethods;
    }

    public void setSuggestMethods(boolean suggestMethods) {
        this.suggestMethods = suggestMethods;
    }

    public MethodRegistry() {
        this(new ObjectMapper());
    }

    public MethodRegistry(ObjectMapper objectMapper) {
        this.objectMapper = requireNonNull(objectMapper);
        this.scanner = new StandardMethodIntrospector(objectMapper);
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public void scan(Object o) {
        for (MethodDefinition method : scanner.findMethods(o)) {
            methodsByName.put(getName(method), method);
        }
    }

    public List<String> suggestMethods(String methodName) {
        TreeMultimap<Integer, String> suggestionsByDistance = TreeMultimap.create();
        for (String name : methodsByName.keySet()) {
            int distance = getLevenshteinDistance(name, methodName, 25);
            if (distance != -1) {
                suggestionsByDistance.put(distance, name);
            }
        }

        return suggestionsByDistance.entries().stream().limit(5).map(Map.Entry::getValue).collect(toList());
    }

    public JsonNode invoke(String methodName, JsonNode args) throws MethodNotFoundException {
        if (!args.isArray() && !args.isObject()) {
            throw new IllegalArgumentException("arguments must be ARRAY or OBJECT but encountered " + args.getNodeType());
        }

        MethodDefinition method = methodsByName.get(methodName);
        if (method == null) {
            throw MethodNotFoundException.forMethod(methodName, suggestMethods ? suggestMethods(methodName) : null);
        }

        Object[] boundArgs = bindArgs(method, args);
        try {
            return invoke(method, boundArgs);

        } catch (IllegalAccessException e) {
            method.getMethod().setAccessible(true);

            try {
                return invoke(method, boundArgs);

            } catch (IOException | IllegalAccessException e2) {
                throw propagate(e2);
            }

        } catch (IOException e) {
            throw propagate(e);
        }
    }

    protected JsonNode invoke(MethodDefinition method, Object[] boundArgs) throws IOException, IllegalAccessException {
        try {
            Object result = method.getMethod().invoke(method.getOwner(), boundArgs);

            TokenBuffer buffer = new TokenBuffer(objectMapper, false);
            objectMapper.writerFor(method.getReturnTypeRef()).writeValue(buffer, result);
            return objectMapper.readTree(buffer.asParser());

        } catch (InvocationTargetException e) {
            throw propagate(e.getCause());
        }
    }

    private Object[] bindArgs(MethodDefinition method, JsonNode args) {
        if (args.isArray()) {
            return bindPositionalArguments(method, (ArrayNode) args);
        }

        return bindNamedArguments(method, (ObjectNode) args);
    }

    private Object[] bindNamedArguments(MethodDefinition method, ObjectNode args) {
        Object[] boundArgs = new Object[method.getParameters().size()];
        List<ParameterDefinition> params = method.getParameters();

        int consumedArgCount = 0;
        int i = 0;
        for (ParameterDefinition p : params) {
            JsonNode arg = args.get(p.getName());

            if (!args.has(p.getName())) {
                if (p.getDefaultValueSupplier().isPresent()) {
                    boundArgs[i++] = p.getDefaultValueSupplier().get().get();
                    continue;
                } else {
                    throw new MissingArgumentException(p.getName());
                }
            }

            if (isLikeNull(arg) && !p.isNullable()) {
                throw new NullArgumentException(p.getName());
            }

            try {
                boundArgs[i++] = objectMapper.convertValue(arg, p.getType());
                consumedArgCount++;
            } catch (Exception e) {
                throw new ParameterBindingException(p.getName(), buildParamBindingErrorMessage(p, arg, e));
            }
        }

        if (consumedArgCount != args.size()) {
            Set<String> parameterNames = params.stream().map(ParameterDefinition::getName).collect(toSet());
            Set<String> argumentNames = ImmutableSet.copyOf(args.fieldNames());
            Set<String> extraArguments = Sets.difference(argumentNames, parameterNames);
            if (!extraArguments.isEmpty()) {
                throw new ParameterBindingException(null, "unrecognized argument names: " + extraArguments);
            }
        }

        return boundArgs;
    }

    private String getName(MethodDefinition method) {
        return method.getQualifiedName(namespaceSeparator);
    }

    private Object[] bindPositionalArguments(MethodDefinition method, ArrayNode args) {
        Object[] boundArgs = new Object[method.getParameters().size()];
        List<ParameterDefinition> params = method.getParameters();

        if (args.size() > params.size()) {
            throw new TooManyPositionalArguments(params.size(), args.size());
        }

        for (int i = 0; i < params.size(); i++) {
            ParameterDefinition param = params.get(i);

            if (!args.has(i)) {
                if (param.getDefaultValueSupplier().isPresent()) {
                    boundArgs[i] = param.getDefaultValueSupplier().get().get();
                    continue;
                } else {
                    throw new MissingArgumentException(param.getName());
                }
            }

            JsonNode arg = args.get(i);
            if (isLikeNull(arg) && !param.isNullable()) {
                throw new NullArgumentException(param.getName());
            }

            try {
                boundArgs[i] = objectMapper.convertValue(arg, param.getType());
            } catch (Exception e) {
                throw new ParameterBindingException(param.getName(), buildParamBindingErrorMessage(param, arg, e));
            }
        }
        return boundArgs;
    }

    private String buildParamBindingErrorMessage(ParameterDefinition param, JsonNode arg, Exception e) {
        String jacksonErrorMessage = e.getMessage().replace("\n at [Source: N/A; line: -1, column: -1]", "");

        String typeName = param.getType().getType().toString();
        return "Can't bind parameter '" + param.getName() + "' of type " + typeName + " to " +
                arg.getNodeType() + " value " + arg.toString() + " : " + jacksonErrorMessage;
    }

    public Collection<MethodDefinition> getMethods() {
        return unmodifiableCollection(methodsByName.values());
    }
}
