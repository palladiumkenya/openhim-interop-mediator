/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.openhim.mediator.emrInterop;

import akka.actor.ActorRef;
import akka.actor.ActorSelection;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.util.OperationOutcomeUtil;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClients;
import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.openhim.mediator.engine.MediatorConfig;
import org.openhim.mediator.engine.messages.FinishRequest;
import org.openhim.mediator.engine.messages.MediatorHTTPRequest;
import org.openhim.mediator.engine.messages.MediatorHTTPResponse;

import javax.annotation.Nonnull;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;


public class SHRIntegrationProxyHandler extends UntypedActor {
    private static class FhirValidationResult {
        boolean passed;
        IBaseOperationOutcome operationOutcome;
    }

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

    private FhirContext fhirContext;
    private ActorRef requestHandler;
    private ActorRef respondTo;
    private MediatorHTTPRequest request;
    private MediatorHTTPResponse response;
    private String openhimTrxID;
    private String upstreamFormat;


    public SHRIntegrationProxyHandler(MediatorConfig config) {
        this.config = config;
    }


    private void loadFhirContext() {
        ActorSelection actor = getContext().actorSelection(config.userPathFor("fhir-context"));
        actor.tell(new FhirContextActor.FhirContextRequest(requestHandler, getSelf()), getSelf());
    }


    private FhirValidationResult validateFhirRequest(Contents contents) {
        FhirValidationResult result = new FhirValidationResult();
        FhirValidator validator = fhirContext.newValidator();

        IParser parser = newParser(contents.contentType);
        IBaseResource resource = parser.parseResource(contents.content);
        ValidationResult vr = validator.validateWithResult(resource);

        if (vr.isSuccessful()) {
            result.passed = true;
        } else {
            result.passed = false;
            result.operationOutcome = vr.toOperationOutcome();
        }

        return result;
    }


    private void forwardRequest(Map<String, String> headers, String body) {
        String upstreamAccept = determineTargetContentType(determineClientContentType());
        headers.put("Accept", upstreamAccept);

        MediatorHTTPRequest newRequest = new MediatorHTTPRequest(
                requestHandler,
                getSelf(),
                "FHIR Upstream",
                request.getMethod(),
                (String) config.getDynamicConfig().get("upstream-scheme"),
                (String) config.getDynamicConfig().get("upstream-host"),
                ((Double) config.getDynamicConfig().get("upstream-port")).intValue(),
                "/fhir/Patient",
                body,
                headers,
                copyParams(request.getParams())
        );

        log.info("[" + openhimTrxID + "] Forwarding to " + newRequest.getHost() + ":" + newRequest.getPort() + newRequest.getPath());

        ActorSelection httpConnector = getContext().actorSelection(config.userPathFor("http-connector"));
        httpConnector.tell(newRequest, getSelf());
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

    private void forwardRequest(Contents contents) {
        Map<String, String> headers = copyHeaders(request.getHeaders());
        headers.put("Content-Type", contents.contentType);
        forwardRequest(headers, contents.content);
    }

    private void forwardRequest() {
        Map<String, String> headers = copyHeaders(request.getHeaders());
        forwardRequest(headers, null);
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

    private void processRequestWithContentsTwo() throws Exception {
        String contentType = request.getHeaders().get("Content-Type");
        String body = request.getBody();

        Contents contents = new Contents(contentType, body);
        forwardRequest(contents);
    }


    private void processRequestWithContents() throws Exception {
        String contentType = request.getHeaders().get("Content-Type");
        String body = request.getBody();
        IParser parser = fhirContext.newJsonParser();
        IBaseResource resource = parser.parseResource(body);
        if (resource.getClass().getSimpleName().equals("Bundle")) {
            Bundle bundle = (Bundle) resource;
            Bundle subjectResource = fetchPatientResource("MOH1667372638");
            getPatientUpiNumber("3aa77935-7043-47d6-bc46-89b9ca4cb27d");

            if (bundle.hasEntry()) {
                Reference subjectRef = new Reference();
                if (subjectResource.hasEntry()) {
                    subjectRef = createPatientReference((Patient) subjectResource.getEntry().get(0).getResource());
                }
                Reference practitionerRef = createPractitionerReferenceBase((Practitioner) fetchFhirResource("Practitioner", Constants.INTEROP_PROVIDER_UUID));
                Encounter.EncounterParticipantComponent participantComponent = new Encounter.EncounterParticipantComponent();
                participantComponent.setIndividual(practitionerRef);

                Bundle facilityResource = fetchLocationResource("10538");
                Location facility = new Location();
                if (facilityResource.hasEntry()) {
                    facility = (Location) facilityResource.getEntry().get(0).getResource();
                }

                for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
                    String resourceType = entry.getResource().getResourceType().toString();
                    switch (resourceType) {
                        case "Observation":
                            Observation observation = (Observation) entry.getResource();
                            observation.setSubject(subjectRef);
                            entry.setResource(observation);
                            break;
                        case "Encounter":
                            Encounter encounter = (Encounter) entry.getResource();
                            encounter.setSubject(subjectRef);
                            entry.setResource(encounter);
                            encounter.setParticipant(new ArrayList<>());
                            encounter.addParticipant(participantComponent);
                            encounter.getLocationFirstRep().setLocation(createLocationReference(facility));
                            break;
                        default:
                            log.error("default logging");
                    }

                }

            }

        }

        Contents contents = new Contents(contentType, body);

        if ((Boolean) config.getDynamicConfig().get("validation-enabled")) {
            FhirValidationResult validationResult = validateFhirRequest(contents);

            if (!validationResult.passed) {
                sendBadRequest(validationResult.operationOutcome);
            }
        }


//        forwardRequest(contents);
    }

    public void getPatientUpiNumber(String patientUuid) throws Exception {
        String url = "";

        HttpClient httpClient = HttpClients.createDefault();
        HttpGet httpGet = new HttpGet(url);


        CredentialsProvider provider = new BasicCredentialsProvider();
        provider.setCredentials(
                AuthScope.ANY,
                new UsernamePasswordCredentials("{username}", "{password}")
        );

        final String auth = "{username}:{password}";
        final byte[] encodedAuth = Base64.encodeBase64(auth.getBytes(StandardCharsets.ISO_8859_1));
        final String authHeader = "Basic " + new String(encodedAuth);
        httpGet.setHeader(HttpHeaders.AUTHORIZATION, authHeader);

        HttpResponse response = httpClient.execute(httpGet);
        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode >= 200 && statusCode < 300) {
            System.out.println("Message resource was successfully posted to the OpenHIM channel");
        } else if (statusCode == 500) {
            System.out.println("Message successfully sent but not delivered to recipient");
        } else {
            String responseBody = response.getEntity().toString();
            System.out.println("An error occurred while posting the message to the OpenHIM channel. Status code: "
                    + statusCode + " Response body: " + responseBody);
        }
    }

    private String getResourceUuid(String resourceUrl) {
        String[] sepUrl = resourceUrl.split("/");
        return sepUrl[sepUrl.length - 3];
    }

    private Reference createPatientReference(@Nonnull Patient patient) {
        Reference reference = new Reference().setReference("/Patient/" + getResourceUuid(patient.getId()))
                .setType("Patient");
        return reference;
    }

    private Reference createPractitionerReferenceBase(@Nonnull Practitioner practitioner) {
        Reference reference = (new Reference()).setReference("Practitioner/" + getResourceUuid(practitioner.getId())).setType("Practitioner");
        if (!practitioner.getName().isEmpty()) {
            reference.setDisplay(practitioner.getName().get(0).getGivenAsSingleString());
        }

        return reference;
    }

    protected Reference createLocationReference(@Nonnull Location location) {
        Reference reference = (new Reference()).setReference("Location/" + getResourceUuid(location.getId())).setType("Location");
        if (!location.getName().isEmpty()) {
            reference.setDisplay(location.getName());
        }

        return reference;
    }

    private IGenericClient getFhirClient() {
        FhirContext fhirContextNew = FhirContext.forR4();
        String serverUrl = "http://localhost:8098/fhir/";

        fhirContextNew.getRestfulClientFactory().setSocketTimeout(200 * 1000);

        IGenericClient client = fhirContextNew.getRestfulClientFactory().newGenericClient(serverUrl);
        return client;
    }

    public Bundle fetchPatientResource(String identifier) {
        try {

            IGenericClient client = getFhirClient();

            Bundle resource = client.search().forResource("Patient").where(Patient.IDENTIFIER.exactly().code(identifier))
                    .returnBundle(Bundle.class).execute();
            log.error("resource " + resource.hasEntry());
            return resource;
        } catch (Exception e) {
            log.error(String.format("Failed fetching FHIR resource %s", e));
            return null;
        }
    }

    public Bundle fetchLocationResource(String identifier) {
        try {
            IGenericClient client = getFhirClient();
            Bundle resource = client.search().forResource("Location").where(Location.IDENTIFIER.exactly().code(identifier))
                    .returnBundle(Bundle.class).execute();
            return resource;
        } catch (Exception e) {
            log.error(String.format("Failed fetching FHIR resource %s", e));
            return null;
        }
    }

    public Resource fetchFhirResource(String resourceType, String resourceId) {
        try {
            IGenericClient client = getFhirClient();
            IBaseResource resource = client.read().resource(resourceType).withId(resourceId).execute();
            return (Resource) resource;
        } catch (Exception e) {
            log.error(String.format("Failed fetching FHIR %s resource with Id %s: %s", resourceType, resourceId, e));
            return null;
        }
    }

    private void processClientRequest() {
        try {
            if (request.getMethod().equalsIgnoreCase("POST") || request.getMethod().equalsIgnoreCase("PUT")) {
                try {
                    processRequestWithContents();
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                } catch (InvocationTargetException e) {
                    throw new RuntimeException(e);
                } catch (NoSuchMethodException e) {
                    throw new RuntimeException(e);
                } catch (InstantiationException e) {
                    throw new RuntimeException(e);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } else {
                forwardRequest();
            }
        } catch (DataFormatException ex) {
            sendBadRequest(throwableToOperationOutcome(ex));
        }
    }

    private IBaseOperationOutcome throwableToOperationOutcome(Throwable ex) {
        IBaseOperationOutcome outcome = OperationOutcomeUtil.newInstance(fhirContext);
        OperationOutcomeUtil.addIssue(fhirContext, outcome, "error", ex.getMessage(), null, null);
        return outcome;
    }

    private void sendBadRequest(IBaseOperationOutcome outcome) {
        String responseContentType = determineClientContentType();

        IParser parser = newParser(responseContentType);
        String body = parser.encodeResourceToString(outcome);

        FinishRequest badRequest = new FinishRequest(body, responseContentType, HttpStatus.SC_BAD_REQUEST);
        requestHandler.tell(badRequest, getSelf());
    }


    private IParser newParser(String contentType) {
        if (contentType.contains("json")) {
            return fhirContext.newJsonParser();
        } else {
            return fhirContext.newXmlParser();
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

    private Contents convertResponseContents(String clientAccept, Contents responseContents) {
        log.info("[" + openhimTrxID + "] Converting response body to " + clientAccept);

        IParser inParser = newParser(responseContents.contentType);
        IBaseResource resource = inParser.parseResource(responseContents.content);

        IParser outParser = newParser(clientAccept);
        String converted = outParser.setPrettyPrint(true).encodeResourceToString(resource);
        return new Contents(clientAccept, converted);
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
                respondWithContents(convertResponseContents(clientAccept, contents));
            }
        }
    }


    @Override
    public void onReceive(Object msg) throws Exception {
        if (msg instanceof MediatorHTTPRequest) {
            System.out.println("SetUp fhir context");

            request = (MediatorHTTPRequest) msg;
            requestHandler = request.getRequestHandler();
            respondTo = request.getRespondTo();
            openhimTrxID = request.getHeaders().get("X-OpenHIM-TransactionID");
            upstreamFormat = (String) config.getDynamicConfig().get("upstream-format");
            loadFhirContext();

        } else if (msg instanceof FhirContextActor.FhirContextResponse) {
            System.out.println("Process request and forward to upstream server" );

            fhirContext = ((FhirContextActor.FhirContextResponse) msg).getResponseObject();
            processRequestWithContentsTwo();

        } else if (msg instanceof MediatorHTTPResponse) {
            System.out.println("Get response from upstream server and propagate back to OpenHIM " );

            response = (MediatorHTTPResponse) msg;
            processUpstreamResponse();
        } else {
            unhandled(msg);
        }
    }
}