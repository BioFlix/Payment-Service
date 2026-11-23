package bioflix.payment.controller;

import bioflix.payment.dto.PaymentResponse;
import bioflix.payment.entity.PaymentStatus;
import bioflix.payment.exception.NotFoundException;
import bioflix.payment.service.PaymentService;
import bioflix.payment.stripe.StripeGateway;
import bioflix.payment.stripe.StripeWebhookService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({PaymentController.class, StripeWebhookController.class})
class PaymentControllerTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Autowired MockMvc mvc;
    @MockitoBean PaymentService paymentService;
    @MockitoBean StripeGateway stripeGateway;
    @MockitoBean StripeWebhookService webhookService;

    @Test
    void checkoutForTheOwner() throws Exception {
        UUID bookingId = UUID.randomUUID();
        when(paymentService.getOrCreateCheckout(bookingId, ALICE, false)).thenReturn(pending(bookingId));

        mvc.perform(get("/api/payments/booking/" + bookingId).header("X-User-Id", ALICE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(13500))
                .andExpect(jsonPath("$.checkoutUrl").value("https://checkout.stripe.com/c/pay/cs_test_1"));
    }

    @Test
    void unknownOrForeignPaymentIs404AndMissingUserIs401() throws Exception {
        UUID bookingId = UUID.randomUUID();
        when(paymentService.getOrCreateCheckout(bookingId, ALICE, false))
                .thenThrow(new NotFoundException("No payment for booking " + bookingId));

        mvc.perform(get("/api/payments/booking/" + bookingId).header("X-User-Id", ALICE.toString()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/payments/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminListNeedsAdminRole() throws Exception {
        mvc.perform(get("/api/payments").header("X-User-Role", "CUSTOMER")).andExpect(status().isForbidden());

        when(paymentService.list(PaymentStatus.COMPLETED, 100)).thenReturn(List.of());
        mvc.perform(get("/api/payments").param("status", "COMPLETED").header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk());
    }

    @Test
    void webhookWithBadSignatureIs400() throws Exception {
        when(stripeGateway.isEnabled()).thenReturn(true);
        doThrow(new StripeGateway.InvalidSignatureException("Invalid Stripe signature"))
                .when(webhookService).handle(anyString(), anyString());

        mvc.perform(post("/api/payments/webhook/stripe").header("Stripe-Signature", "t=1,v1=bad").content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void devEndpointsOnlyWorkInDevModeAndWebhookOnlyWithStripe() throws Exception {
        UUID bookingId = UUID.randomUUID();
        when(stripeGateway.isEnabled()).thenReturn(true);
        mvc.perform(post("/api/payments/dev/" + bookingId + "/succeed")).andExpect(status().isNotFound());

        when(stripeGateway.isEnabled()).thenReturn(false);
        mvc.perform(post("/api/payments/webhook/stripe").header("Stripe-Signature", "x").content("{}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(webhookService);
    }

    private static PaymentResponse pending(UUID bookingId) {
        return new PaymentResponse(UUID.randomUUID(), bookingId, ALICE, 13500, "DKK", PaymentStatus.PENDING,
                "https://checkout.stripe.com/c/pay/cs_test_1", Instant.parse("2030-01-01T12:30:00Z"),
                null, null, null, Instant.parse("2030-01-01T12:00:00Z"), null);
    }
}
