package org.openhim.mediator.emrInterop.util;

import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
import ca.uhn.fhir.rest.client.api.IHttpResponse;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.Validate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class BasicAuthInterceptorExtended implements IClientInterceptor {
    private String myUsername;
    private String myPassword;
    private String myHeaderValue;

    public BasicAuthInterceptorExtended(String theCredentialString) {
        Validate.notBlank(theCredentialString, "theCredentialString must not be null or blank", new Object[0]);
        Validate.isTrue(theCredentialString.contains(":"), "theCredentialString must be in the format 'username:password'", new Object[0]);
        String encoded = new String(Base64.encodeBase64(theCredentialString.getBytes(Constants.CHARSET_US_ASCII)), StandardCharsets.UTF_8);
        this.myHeaderValue = "Basic " + encoded;
    }

    public void interceptRequest(IHttpRequest theRequest) {
        theRequest.addHeader("Authorization", this.myHeaderValue);
    }

    public void interceptResponse(IHttpResponse theResponse) throws IOException {
    }
}
