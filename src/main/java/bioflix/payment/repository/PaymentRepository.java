package bioflix.payment.repository;

import bioflix.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByBookingId(Long bookingId);

    Optional<Payment> findByStripeSessionId(String stripeSessionId);

    boolean existsByBookingId(Long bookingId);

    List<Payment> findByUserIdOrderByCreatedAtDesc(Long userId);
}
