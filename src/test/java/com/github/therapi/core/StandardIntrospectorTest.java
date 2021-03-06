package com.github.therapi.core;

import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;

import java.util.Collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.therapi.core.annotation.Default;
import com.github.therapi.core.annotation.Remotable;
import com.github.therapi.core.internal.MethodDefinition;
import com.google.common.collect.ImmutableList;
import org.junit.Test;

public class StandardIntrospectorTest {
    @Remotable("foo")
    public interface FooService {
        String greet(@Default("stranger") String name);
    }

    public static class FooServiceImpl implements FooService {
        @Override
        public String greet(String name) {
            return "Hello " + name;
        }
    }

    @Test
    public void testScan() throws Exception {
        Collection<MethodDefinition> methods = new StandardMethodIntrospector(new ObjectMapper()).findMethods(new FooServiceImpl());

        assertEquals(ImmutableList.of("greet"),
                methods.stream().map(MethodDefinition::getUnqualifiedName).collect(toList()));
    }
}