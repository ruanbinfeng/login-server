/*******************************************************************************
 *     Cloud Foundry 
 *     Copyright (c) [2009-2014] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/
package org.cloudfoundry.identity.uaa.login;

import org.cloudfoundry.identity.uaa.error.UaaException;
import org.cloudfoundry.identity.uaa.login.test.ThymeleafConfig;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.thymeleaf.spring4.SpringTemplateEngine;

import java.util.Map;

import static org.mockito.Matchers.contains;
import static org.mockito.Matchers.eq;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = ThymeleafConfig.class)
public class EmailResetPasswordServiceTests {

    private EmailResetPasswordService emailResetPasswordService;
    private MockRestServiceServer mockUaaServer;
    private MockEnvironment mockEnvironment;
    private EmailService emailService;

    @Autowired
    @Qualifier("mailTemplateEngine")
    SpringTemplateEngine templateEngine;

    @Before
    public void setUp() throws Exception {
        RestTemplate uaaTemplate = new RestTemplate();
        mockUaaServer = MockRestServiceServer.createServer(uaaTemplate);
        emailService = Mockito.mock(EmailService.class);
        mockEnvironment = new MockEnvironment();
        emailResetPasswordService = new EmailResetPasswordService(templateEngine, emailService, uaaTemplate, "http://uaa.example.com/uaa", "pivotal", "reset_password_kind_id", mockEnvironment);
    }

    @Test
    public void testForgotPasswordWhenAResetCodeIsReturnedByTheUaa() throws Exception {
        mockUaaServer.expect(requestTo("http://uaa.example.com/uaa/password_resets"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"code\":\"the_secret_code\",\"userId\":\"id001\"}", APPLICATION_JSON));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setProtocol("http");
        request.setContextPath("/login");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        emailResetPasswordService.forgotPassword("user@example.com");

        mockUaaServer.verify();

        Mockito.verify(emailService).sendMimeMessage(
                eq("user@example.com"),
                eq("Pivotal account password reset request"),
                contains("<a href=\"http://localhost/login/reset_password?code=the_secret_code&amp;email=user%40example.com\">Reset your password</a>")
        );
    }

    @Test
    public void testForgotPasswordWhenConflictIsReturnedByTheUaa() throws Exception {
        mockUaaServer.expect(requestTo("http://uaa.example.com/uaa/password_resets"))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setProtocol("http");
        request.setContextPath("/login");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        emailResetPasswordService.forgotPassword("user@example.com");

        mockUaaServer.verify();

        Mockito.verify(emailService).sendMimeMessage(
            eq("user@example.com"),
            eq("Pivotal account password reset request"),
            contains("Your account credentials for localhost are managed by an external service. Please contact your administrator for password recovery requests.")
        );
    }

    @Test
    public void testForgotPasswordWhenTheCodeIsDenied() throws Exception {
        mockUaaServer.expect(requestTo("http://uaa.example.com/uaa/password_resets"))
                .andExpect(method(POST))
                .andRespond(withBadRequest());

        emailResetPasswordService.forgotPassword("user@example.com");

        mockUaaServer.verify();

        Mockito.verifyZeroInteractions(emailService);
    }

    @Test
    public void testForgotPasswordUsingNotificationsService() throws Exception {
        mockEnvironment.setProperty("notifications.url", "example.com");

        mockUaaServer.expect(requestTo("http://uaa.example.com/uaa/password_resets"))
            .andExpect(method(POST))
            .andRespond(withSuccess("{\"code\":\"the_secret_code\",\"userId\":\"id001\"}", APPLICATION_JSON));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setProtocol("http");
        request.setContextPath("/login");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        emailResetPasswordService.forgotPassword("user@example.com");

        mockUaaServer.verify();

        Mockito.verify(emailService).sendNotification(
            eq("id001"),
            eq("reset_password_kind_id"),
            eq("Pivotal account password reset request"),
            contains("<a href=\"http://localhost/login/reset_password?code=the_secret_code&amp;email=user%40example.com\">Reset your password</a>")
        );
    }

    @Test
    public void testResetPassword() throws Exception {
        mockUaaServer.expect(requestTo("http://uaa.example.com/uaa/password_change"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.code").value("secret_code"))
                .andExpect(jsonPath("$.new_password").value("new_secret"))
                .andRespond(withSuccess("{" +
                    "  \"user_id\":\"usermans-id\"," +
                    "  \"username\":\"userman\"" +
                    "}", APPLICATION_JSON));

        Map<String,String> userInfo = emailResetPasswordService.resetPassword("secret_code", "new_secret");

        mockUaaServer.verify();

        Assert.assertThat(userInfo, Matchers.hasEntry("user_id", "usermans-id"));
        Assert.assertThat(userInfo, Matchers.hasEntry("username", "userman"));
    }

    @Test(expected = UaaException.class)
    public void testResetPasswordWhenTheCodeIsDenied() throws Exception {
        mockUaaServer.expect(requestTo("http://uaa.example.com/uaa/password_change"))
                .andExpect(method(POST))
                .andRespond(withBadRequest());

        emailResetPasswordService.resetPassword("b4d_k0d3z", "new_password");

        mockUaaServer.verify();
    }
}
