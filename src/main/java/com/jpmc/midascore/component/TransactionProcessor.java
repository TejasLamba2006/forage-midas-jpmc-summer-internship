package com.jpmc.midascore.component;

import com.jpmc.midascore.entity.TransactionRecord;
import com.jpmc.midascore.entity.UserRecord;
import com.jpmc.midascore.foundation.Incentive;
import com.jpmc.midascore.foundation.Transaction;
import com.jpmc.midascore.repository.TransactionRepository;
import com.jpmc.midascore.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

@Component
public class TransactionProcessor {
    private static final Logger logger = LoggerFactory.getLogger(TransactionProcessor.class);

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final RestTemplate restTemplate;

    @Value("${incentive.api.url:http://localhost:8080/incentive}")
    private String incentiveApiUrl;

    public TransactionProcessor(UserRepository userRepository, TransactionRepository transactionRepository,
            RestTemplate restTemplate) {
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.restTemplate = restTemplate;
    }

    @Transactional
    public void processTransaction(Transaction transaction) {
        // Validate sender exists
        UserRecord sender = userRepository.findById(transaction.getSenderId());
        if (sender == null) {
            logger.warn("Invalid transaction: Sender with id {} not found", transaction.getSenderId());
            return;
        }

        // Validate recipient exists
        UserRecord recipient = userRepository.findById(transaction.getRecipientId());
        if (recipient == null) {
            logger.warn("Invalid transaction: Recipient with id {} not found", transaction.getRecipientId());
            return;
        }

        // Validate sender has sufficient balance
        if (sender.getBalance() < transaction.getAmount()) {
            logger.warn("Invalid transaction: Sender {} has insufficient balance. Balance: {}, Required: {}",
                    sender.getName(), sender.getBalance(), transaction.getAmount());
            return;
        }

        // Get incentive from the API
        float incentive = 0;
        try {
            Incentive incentiveResponse = restTemplate.postForObject(incentiveApiUrl, transaction, Incentive.class);
            if (incentiveResponse != null) {
                incentive = incentiveResponse.getAmount();
                logger.info("Received incentive: {} for transaction", incentive);
            }
        } catch (Exception e) {
            logger.error("Failed to get incentive from API: {}", e.getMessage());
            // Continue processing transaction without incentive
        }

        // Process the transaction
        sender.setBalance(sender.getBalance() - transaction.getAmount());
        recipient.setBalance(recipient.getBalance() + transaction.getAmount() + incentive);

        // Save updated balances
        userRepository.save(sender);
        userRepository.save(recipient);

        // Record the transaction
        TransactionRecord transactionRecord = new TransactionRecord(sender, recipient, transaction.getAmount(),
                incentive);
        transactionRepository.save(transactionRecord);

        logger.info("Transaction processed: {} sent {} to {} (with incentive: {})", sender.getName(),
                transaction.getAmount(), recipient.getName(), incentive);
    }
}
