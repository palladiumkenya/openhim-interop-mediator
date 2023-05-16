/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.openhim.mediator.hl7messageHandler;

import akka.actor.ActorRef;
import akka.actor.ActorSelection;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.tuple.Pair;
import org.openhim.mediator.engine.MediatorConfig;
import org.openhim.mediator.engine.messages.FinishRequest;
import org.openhim.mediator.engine.messages.MediatorHTTPRequest;
import org.openhim.mediator.engine.messages.MediatorHTTPResponse;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;


public class HL7MessageProxyHandler extends UntypedActor {

    private static class Contents {
        String contentType;
        String content;

        public Contents(String contentType, String content) {
            this.contentType = contentType;
            this.content = content;
        }
    }

    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    private final MediatorConfig config;
    private ActorRef requestHandler;
    private ActorRef respondTo;
    private MediatorHTTPRequest request;
    private MediatorHTTPResponse response;
    private String openhimTrxID;
    private String upstreamFormat;


    public HL7MessageProxyHandler(MediatorConfig config) {
        this.config = config;
    }

    private void forwardRequest(Map<String, String> headers, String body) {
        String upstreamAccept = determineTargetContentType(determineClientContentType());
        headers.put("Accept", upstreamAccept);

        String subscribers = (String) config.getDynamicConfig().get("participating-systems");

        List<String> list = Arrays.asList(subscribers.split(","));
        List<MessageSubscriber> messageSubscribers = new ArrayList<>();
        list.forEach(e -> {
            String[] value = e.split("::");
            MessageSubscriber subscriber = new MessageSubscriber(value[0], value[1]);
            messageSubscribers.add(subscriber);
        });

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = null;
        try {
            jsonNode = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        ObjectNode patientObj = (ObjectNode) jsonNode.get("message_header");

        String messageType = patientObj.get("message_type").asText();

        List<MessageSubscriber> finalList = new ArrayList<>();

        messageSubscribers.forEach(s -> {
            if (s.getMessageType().equals(messageType)) {
                finalList.add(s);
            }
        });

        System.out.println("vasgxhasvc " + finalList.size());
        if (finalList.isEmpty()) return;

        finalList.forEach(e -> {
            System.out.println("Sending message of type "+messageType+ " to upstream server "+e.getServerUrl());

            try {
                URL url = new URL(e.getServerUrl());
                MediatorHTTPRequest newRequest = new MediatorHTTPRequest(
                        requestHandler,
                        getSelf(),
                        "Upstream Server",
                        request.getMethod(),
                        url.getProtocol(),
                        url.getHost(),
                        url.getPort(),
                        url.getPath(),
                        body,
                        headers,
                        copyParams(request.getParams())
                );

                log.info("[" + openhimTrxID + "] Forwarding to " + newRequest.getHost() + ":" + newRequest.getPort() + newRequest.getPath());

                ActorSelection httpConnector = getContext().actorSelection(config.userPathFor("http-connector"));
                httpConnector.tell(newRequest, getSelf());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

    }

    private Map<String, String> copyHeaders(Map<String, String> headers) {
        Map<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String header : headers.keySet()) {
            if ("Content-Type".equalsIgnoreCase(header) || "Content-Length".equalsIgnoreCase(header) || "Host".equalsIgnoreCase(header)) {
                continue;
            }

            copy.put(header, headers.get(header));
        }
        return copy;
    }

    private List<Pair<String, String>> copyParams(List<Pair<String, String>> params) {
        List<Pair<String, String>> copy = new ArrayList<>();
        for (Pair<String, String> param : params) {
            if ("_format".equalsIgnoreCase(param.getKey())) {
                continue;
            }

            copy.add(param);
        }
        return copy;
    }

    private void processRequestWithContents() {
        String contentType = request.getHeaders().get("Content-Type");
        String body = request.getBody();
        Contents contents = new Contents(contentType, body);
        if (contents == null) {
            log.info("HL7 MESSAGE NOT PROVIDED");
            return;
        }


        Map<String, String> headers = copyHeaders(request.getHeaders());
        headers.put("Content-Type", contents.contentType);
        forwardRequest(headers, contents.content);
    }

    private String determineTargetContentType(String fromContentType) {
        String contentType = Constants.FHIR_MIME_JSON;
        if ("XML".equalsIgnoreCase(upstreamFormat) ||
                ("Client".equalsIgnoreCase(upstreamFormat) && fromContentType.contains("xml"))) {
            contentType = Constants.FHIR_MIME_XML;
        }
        return contentType;
    }

    private boolean isUpstreamAndClientFormatsEqual(String clientContentType) {
        return ("JSON".equalsIgnoreCase(upstreamFormat) && clientContentType.contains("json")) ||
                ("XML".equalsIgnoreCase(upstreamFormat) && clientContentType.contains("xml"));
    }

    private void processClientRequest() {
        if (request.getMethod().equalsIgnoreCase("POST") || request.getMethod().equalsIgnoreCase("PUT")) {
            processRequestWithContents();
        } else {
            log.info("REQUEST TYPE NOT SUPPORTED");
        }
    }

    private String determineClientContentType() {
        // first check for Accept header
        String accept = request.getHeaders().get("Accept");
        if (accept != null && !"*/*".equals(accept)) {
            return accept;
        }

        // secondly for _format param
        for (Pair<String, String> param : request.getParams()) {
            if (param.getKey().equals("_format")) {
                return param.getValue();
            }
        }

        // thirdly check for the format the client sent content with
        String contentType = request.getHeaders().get("Content-Type");
        if (contentType != null) {
            return contentType.contains("json") ? Constants.FHIR_MIME_JSON : Constants.FHIR_MIME_XML;
        }

        // else use JSON as a default
        return Constants.FHIR_MIME_JSON;
    }


    private Contents getResponseBodyAsContents() {
        String contentType = response.getHeaders().get("Content-Type");
        String body = response.getBody();

        if (body == null || body.trim().isEmpty()) {
            return null;
        }

        if (contentType == null || !contentType.contains("json") && !contentType.contains("xml")) {
            return null;
        }

        return new Contents(contentType, body);
    }

    private void respondWithContents(Contents contents) {
        Map<String, String> headers = copyHeaders(response.getHeaders());
        headers.put("Content-Type", contents.contentType);
        FinishRequest fr = new FinishRequest(contents.content, headers, response.getStatusCode());
        respondTo.tell(fr, getSelf());
    }

    private void processUpstreamResponse() {
        log.info("[" + openhimTrxID + "] Processing upstream response and responding to client");
        Contents contents = getResponseBodyAsContents();

        if ("Client".equalsIgnoreCase(upstreamFormat) || contents == null) {
            respondTo.tell(response.toFinishRequest(true), getSelf());
        } else {
            String clientAccept = determineClientContentType();

            if (isUpstreamAndClientFormatsEqual(clientAccept)) {
                respondWithContents(contents);
            } else {
                //respondWithContents(convertResponseContents(clientAccept, contents));
            }
        }
    }

    @Override
    public void onReceive(Object msg) throws Exception {
        if (msg instanceof MediatorHTTPRequest) { //inbound request
            System.out.println("CALLED");
            request = (MediatorHTTPRequest) msg;
            requestHandler = request.getRequestHandler();
            respondTo = request.getRespondTo();
            openhimTrxID = request.getHeaders().get("X-OpenHIM-TransactionID");
            upstreamFormat = (String) config.getDynamicConfig().get("upstream-format");
            processClientRequest();

        } else if (msg instanceof MediatorHTTPResponse) { //response from upstream server
            response = (MediatorHTTPResponse) msg;
            processUpstreamResponse();
        } else {
            unhandled(msg);
        }
    }
}