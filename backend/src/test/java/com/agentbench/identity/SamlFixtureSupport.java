package com.agentbench.identity;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.opensaml.core.xml.XMLObjectBuilderFactory;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.io.Marshaller;
import org.opensaml.core.xml.schema.XSString;
import org.opensaml.saml.common.SAMLVersion;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Attribute;
import org.opensaml.saml.saml2.core.AttributeStatement;
import org.opensaml.saml.saml2.core.AttributeValue;
import org.opensaml.saml.saml2.core.Audience;
import org.opensaml.saml.saml2.core.AudienceRestriction;
import org.opensaml.saml.saml2.core.AuthnContext;
import org.opensaml.saml.saml2.core.AuthnContextClassRef;
import org.opensaml.saml.saml2.core.AuthnStatement;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.Status;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.saml.saml2.core.Subject;
import org.opensaml.saml.saml2.core.SubjectConfirmation;
import org.opensaml.saml.saml2.core.SubjectConfirmationData;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.support.SignatureConstants;
import org.opensaml.xmlsec.signature.support.Signer;
import org.springframework.security.saml2.core.OpenSamlInitializationService;
import org.w3c.dom.Element;

import javax.xml.namespace.QName;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Builds fixture IdP metadata + signed SAMLResponses for CI (no live IdP).
 */
final class SamlFixtureSupport {

    static {
        OpenSamlInitializationService.initialize();
    }

    record IdpKeys(KeyPair keyPair, X509Certificate certificate, String entityId) {
        String metadataXml(String ssoUrl) {
            try {
                String certB64 = Base64.getEncoder().encodeToString(certificate.getEncoded());
                return """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" entityID="%s">
                          <md:IDPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol"
                              WantAuthnRequestsSigned="false">
                            <md:KeyDescriptor use="signing">
                              <ds:KeyInfo xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
                                <ds:X509Data>
                                  <ds:X509Certificate>%s</ds:X509Certificate>
                                </ds:X509Data>
                              </ds:KeyInfo>
                            </md:KeyDescriptor>
                            <md:NameIDFormat>urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress</md:NameIDFormat>
                            <md:SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect"
                                Location="%s"/>
                            <md:SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"
                                Location="%s"/>
                          </md:IDPSSODescriptor>
                        </md:EntityDescriptor>
                        """.formatted(entityId, certB64, ssoUrl, ssoUrl);
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to encode IdP certificate", ex);
            }
        }
    }

    private SamlFixtureSupport() {
    }

    static IdpKeys generateIdp(String entityId) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            X509Certificate certificate = selfSigned(keyPair, entityId);
            return new IdpKeys(keyPair, certificate, entityId);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate IdP keys", ex);
        }
    }

    static String signedResponseBase64(
            IdpKeys idp,
            String spEntityId,
            String acsUrl,
            String inResponseTo,
            String nameId,
            String email,
            String displayName) {
        try {
            Instant now = Instant.now();
            XMLObjectBuilderFactory builders = XMLObjectProviderRegistrySupport.getBuilderFactory();

            Response response = build(builders, Response.DEFAULT_ELEMENT_NAME);
            response.setID("_resp-" + UUID.randomUUID());
            response.setVersion(SAMLVersion.VERSION_20);
            response.setIssueInstant(now);
            response.setDestination(acsUrl);
            response.setInResponseTo(inResponseTo);
            response.setIssuer(issuer(builders, idp.entityId()));

            Status status = build(builders, Status.DEFAULT_ELEMENT_NAME);
            StatusCode statusCode = build(builders, StatusCode.DEFAULT_ELEMENT_NAME);
            statusCode.setValue(StatusCode.SUCCESS);
            status.setStatusCode(statusCode);
            response.setStatus(status);

            Assertion assertion = build(builders, Assertion.DEFAULT_ELEMENT_NAME);
            assertion.setID("_assert-" + UUID.randomUUID());
            assertion.setVersion(SAMLVersion.VERSION_20);
            assertion.setIssueInstant(now);
            assertion.setIssuer(issuer(builders, idp.entityId()));

            Subject subject = build(builders, Subject.DEFAULT_ELEMENT_NAME);
            NameID nameID = build(builders, NameID.DEFAULT_ELEMENT_NAME);
            nameID.setFormat(NameID.EMAIL);
            nameID.setValue(nameId);
            subject.setNameID(nameID);

            SubjectConfirmation confirmation = build(builders, SubjectConfirmation.DEFAULT_ELEMENT_NAME);
            confirmation.setMethod(SubjectConfirmation.METHOD_BEARER);
            SubjectConfirmationData confirmationData = build(builders, SubjectConfirmationData.DEFAULT_ELEMENT_NAME);
            confirmationData.setRecipient(acsUrl);
            confirmationData.setNotOnOrAfter(now.plusSeconds(300));
            confirmationData.setInResponseTo(inResponseTo);
            confirmation.setSubjectConfirmationData(confirmationData);
            subject.getSubjectConfirmations().add(confirmation);
            assertion.setSubject(subject);

            Conditions conditions = build(builders, Conditions.DEFAULT_ELEMENT_NAME);
            conditions.setNotBefore(now.minusSeconds(60));
            conditions.setNotOnOrAfter(now.plusSeconds(300));
            AudienceRestriction audienceRestriction = build(builders, AudienceRestriction.DEFAULT_ELEMENT_NAME);
            Audience audience = build(builders, Audience.DEFAULT_ELEMENT_NAME);
            audience.setURI(spEntityId);
            audienceRestriction.getAudiences().add(audience);
            conditions.getAudienceRestrictions().add(audienceRestriction);
            assertion.setConditions(conditions);

            AuthnStatement authnStatement = build(builders, AuthnStatement.DEFAULT_ELEMENT_NAME);
            authnStatement.setAuthnInstant(now);
            authnStatement.setSessionIndex("_session-" + UUID.randomUUID());
            AuthnContext authnContext = build(builders, AuthnContext.DEFAULT_ELEMENT_NAME);
            AuthnContextClassRef classRef = build(builders, AuthnContextClassRef.DEFAULT_ELEMENT_NAME);
            classRef.setURI(AuthnContext.PPT_AUTHN_CTX);
            authnContext.setAuthnContextClassRef(classRef);
            authnStatement.setAuthnContext(authnContext);
            assertion.getAuthnStatements().add(authnStatement);

            AttributeStatement attributeStatement = build(builders, AttributeStatement.DEFAULT_ELEMENT_NAME);
            attributeStatement.getAttributes().add(stringAttribute(builders, "email", email));
            attributeStatement.getAttributes().add(stringAttribute(builders, "displayName", displayName));
            assertion.getAttributeStatements().add(attributeStatement);

            signAssertion(assertion, idp);
            response.getAssertions().add(assertion);

            String xml = marshall(response);
            return Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build signed SAMLResponse", ex);
        }
    }

    private static void signAssertion(Assertion assertion, IdpKeys idp) throws Exception {
        XMLObjectBuilderFactory builders = XMLObjectProviderRegistrySupport.getBuilderFactory();
        Signature signature = build(builders, Signature.DEFAULT_ELEMENT_NAME);
        BasicX509Credential credential = new BasicX509Credential(idp.certificate(), idp.keyPair().getPrivate());
        signature.setSigningCredential(credential);
        signature.setSignatureAlgorithm(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
        signature.setCanonicalizationAlgorithm(SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
        assertion.setSignature(signature);

        Marshaller marshaller = XMLObjectProviderRegistrySupport.getMarshallerFactory().getMarshaller(assertion);
        marshaller.marshall(assertion);
        Signer.signObject(signature);
    }

    private static Attribute stringAttribute(XMLObjectBuilderFactory builders, String name, String value) {
        Attribute attribute = build(builders, Attribute.DEFAULT_ELEMENT_NAME);
        attribute.setName(name);
        attribute.setNameFormat(Attribute.BASIC);
        XSString attrValue = (XSString) builders.getBuilder(XSString.TYPE_NAME)
                .buildObject(AttributeValue.DEFAULT_ELEMENT_NAME, XSString.TYPE_NAME);
        attrValue.setValue(value);
        attribute.getAttributeValues().add(attrValue);
        return attribute;
    }

    private static Issuer issuer(XMLObjectBuilderFactory builders, String entityId) {
        Issuer issuer = build(builders, Issuer.DEFAULT_ELEMENT_NAME);
        issuer.setValue(entityId);
        return issuer;
    }

    @SuppressWarnings("unchecked")
    private static <T> T build(XMLObjectBuilderFactory builders, QName elementName) {
        return (T) builders.getBuilder(elementName).buildObject(elementName);
    }

    private static String marshall(Response response) throws Exception {
        Marshaller marshaller = XMLObjectProviderRegistrySupport.getMarshallerFactory().getMarshaller(response);
        Element element = marshaller.marshall(response);
        StringWriter writer = new StringWriter();
        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(element), new StreamResult(writer));
        return writer.toString();
    }

    private static X509Certificate selfSigned(KeyPair keyPair, String cn) throws Exception {
        Instant now = Instant.now();
        X500Name name = new X500Name("CN=" + cn);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name,
                BigInteger.valueOf(System.currentTimeMillis()),
                Date.from(now.minusSeconds(3600)),
                Date.from(now.plusSeconds(365L * 24 * 3600)),
                name,
                keyPair.getPublic()
        );
        var holder = builder.build(new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate()));
        return new JcaX509CertificateConverter().getCertificate(holder);
    }
}
