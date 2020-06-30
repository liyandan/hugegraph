/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.api.profile;

import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.security.RolesAllowed;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.NotSupportedException;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;

import org.apache.commons.configuration.MapConfiguration;
import org.slf4j.Logger;

import com.baidu.hugegraph.HugeGraph;
import com.baidu.hugegraph.api.API;
import com.baidu.hugegraph.auth.HugeAuthenticator.RoleAction;
import com.baidu.hugegraph.config.CoreOptions;
import com.baidu.hugegraph.config.HugeConfig;
import com.baidu.hugegraph.core.GraphManager;
import com.baidu.hugegraph.server.RestServer;
import com.baidu.hugegraph.type.define.GraphMode;
import com.baidu.hugegraph.util.E;
import com.baidu.hugegraph.util.Log;
import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableMap;

@Path("graphs")
@Singleton
public class GraphsAPI extends API {

    private static final Logger LOG = Log.logger(RestServer.class);

    private static final String CONFIRM_CLEAR = "I'm sure to delete all data";

    @GET
    @Timed
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    @RolesAllowed({"admin", "$dynamic"})
    public Object list(@Context GraphManager manager,
                       @Context SecurityContext sc) {
        Set<String> graphs = manager.graphs();
        // Filter by user role
        Set<String> filterGraphs = new HashSet<>();
        for (String graph : graphs) {
            String role = RoleAction.roleFor(graph);
            if (sc.isUserInRole(role)) {
                filterGraphs.add(graph);
            }
        }
        return ImmutableMap.of("graphs", filterGraphs);
    }

    @GET
    @Timed
    @Path("{name}")
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    @RolesAllowed({"admin", "$owner=$name"})
    public Object get(@Context GraphManager manager,
                      @PathParam("name") String name) {
        LOG.debug("Get graph by name '{}'", name);

        HugeGraph g = graph(manager, name);
        return ImmutableMap.of("name", g.name(), "backend", g.backend());
    }

    @POST
    @Timed
    @Path("{name}/copy")
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    @RolesAllowed({"admin", "$owner=$name"})
    public Object copy(@Context GraphManager manager,
                       @PathParam("name") String name,
                       JsonGraphParams params) {
        LOG.debug("Create graph with copied config from '{}'", name);
        /*
         * 0. check and modify params
         * 1. init backend store
         * 2. inject graph and traversal source into gremlin server context
         * 3. inject graph into rest server context
         */
        E.checkArgumentNotNull(params.name, "The name of graph can't be null");
        E.checkArgument(!manager.graphs().contains(params.name),
                        "The name of graph has existed");

        HugeGraph g = graph(manager, name);
        HugeConfig config = (HugeConfig) g.configuration();
        HugeConfig copiedConfig = (HugeConfig) config.clone();
        copiedConfig.setProperty(CoreOptions.STORE.name(), params.name);

        HugeGraph graph = manager.createGraph(copiedConfig);
        return ImmutableMap.of("name", graph.name(), "backend", graph.backend());
    }

    @POST
    @Timed
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    @RolesAllowed({"admin", "$owner=$name"})
    public Object create(@Context GraphManager manager,
                         JsonGraphParams params) {
        LOG.debug("Create graph with params '{}'", params);
        E.checkArgumentNotNull(params.name, "The name of graph can't be null");
        E.checkArgument(!manager.graphs().contains(params.name),
                        "The name of graph has existed");
        E.checkArgument(params.options != null && !params.options.isEmpty(),
                        "The options of graph can't be null or empty");

        MapConfiguration mapConfig = new MapConfiguration(params.options);
        HugeConfig config = new HugeConfig(mapConfig);
        config.addProperty(CoreOptions.STORE.name(), params.name);

        HugeGraph graph = manager.createGraph(config);
        return ImmutableMap.of("name", graph.name(), "backend", graph.backend());
    }

    @GET
    @Timed
    @Path("{name}/conf")
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    @RolesAllowed("admin")
    public File getConf(@Context GraphManager manager,
                        @PathParam("name") String name) {
        LOG.debug("Get graph configuration by name '{}'", name);

        HugeGraph g = graph4admin(manager, name);

        HugeConfig config = (HugeConfig) g.configuration();
        File file = config.getFile();
        if (file == null) {
            throw new NotSupportedException("Can't access the api in " +
                      "a node which started with non local file config.");
        }
        return file;
    }

    @DELETE
    @Timed
    @Path("{name}/clear")
    @Consumes(APPLICATION_JSON)
    @RolesAllowed("admin")
    public void clear(@Context GraphManager manager,
                      @PathParam("name") String name,
                      @QueryParam("confirm_message") String message) {
        LOG.debug("Clear graph by name '{}'", name);

        HugeGraph g = graph(manager, name);

        if (!CONFIRM_CLEAR.equals(message)) {
            throw new IllegalArgumentException(String.format(
                      "Please take the message: %s", CONFIRM_CLEAR));
        }
        g.truncateBackend();
    }

    @PUT
    @Timed
    @Path("{name}/mode")
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    @RolesAllowed("admin")
    public Map<String, GraphMode> mode(@Context GraphManager manager,
                                       @PathParam("name") String name,
                                       GraphMode mode) {
        LOG.debug("Set mode to: '{}' of graph '{}'", mode, name);

        E.checkArgument(mode != null, "Graph mode can't be null");
        HugeGraph g = graph(manager, name);
        g.mode(mode);
        return ImmutableMap.of("mode", mode);
    }

    @GET
    @Timed
    @Path("{name}/mode")
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    @RolesAllowed({"admin", "$owner=$name"})
    public Map<String, GraphMode> mode(@Context GraphManager manager,
                                       @PathParam("name") String name) {
        LOG.debug("Get mode of graph '{}'", name);

        HugeGraph g = graph(manager, name);
        return ImmutableMap.of("mode", g.mode());
    }

    private static class JsonGraphParams {

        @JsonProperty("name")
        private String name;
        @JsonProperty("options")
        private Map<String, String> options;

        @Override
        public String toString() {
            return String.format("JsonGraphParams{name=%s, options=%s}",
                                 this.name, this.options);
        }
    }
}
