package bioflix.payment.repository;

import bioflix.payment.entity.Payment;
import bioflix.payment.entity.PaymentStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByBookingId(UUID bookingId);

    Optional<Payment> findByStripeSessionId(String stripeSessionId);

    List<Payment> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Payment> findByStatusOrderByCreatedAtDesc(PaymentStatus status, Limit limit);

    List<Payment> findAllByOrderByCreatedAtDesc(Limit limit);
}
