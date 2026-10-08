package meko.agentic.goap.story;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The simulated borrower's side of the loan story: what they applied for and the documents and checks behind it. Each
 * case gets its own application id, so the conversation keyed by it holds exactly one case and a review never mixes
 * runs. Agents never read this to learn a fact; only the simulated borrower (sending a document), the simulated
 * employer and credit bureau (answering a check) do. What the underwriter decides on is what reached its file.
 * <p>
 * No name, address or national id anywhere: an application is an id, figures and the checks' results.
 */
@Component
public class LoanApplications {

    /**
     * @param amountUsd      the loan amount, USD
     * @param aprPercent     the annual percentage rate offered
     * @param termMonths     the repayment term
     * @param employment     how the borrower earns: "salaried" (paid by an employer, on pay stubs) or "self-employed"
     * @param monthlyIncome  gross monthly income on the pay stubs, USD
     * @param monthlyDebts   monthly obligations on the credit report, USD
     * @param reservesMonths months of instalments plus debts the bank statements cover
     * @param deposit        the largest single deposit on the bank statements, and what the borrower says it was
     * @param gap            the longest break in the employment history, and what the borrower says it was
     */
    public record Application(String caseId, String applicationId, int amountUsd, double aprPercent, int termMonths, String purpose,
                              String employment, int monthlyIncome, int monthlyDebts, int creditScore, int reservesMonths,
                              Deposit deposit, EmploymentGap gap) {

        /** The loan amount as the application states it, "25,000 USD". */
        public String amount() {
            return String.format("%,d USD", amountUsd);
        }

        /** The monthly instalment of a fully amortizing loan at this rate and term, rounded to the dollar. */
        public int instalment() {
            double r = aprPercent / 100 / 12;
            return (int) Math.round(amountUsd * r / (1 - Math.pow(1 + r, -termMonths)));
        }
    }

    /**
     * A single deposit on the bank statements; the borrower's explanation of it and the document behind it.
     *
     * @param kind the kind of source, as an underwriter would generalise it ("a vehicle sale")
     */
    public record Deposit(int amountUsd, String date, String source, String kind, String evidence) {}

    /** A break in employment; the borrower's explanation of it and the document behind it. */
    public record EmploymentGap(int days, String period, String reason, String evidence) {}

    /** The scenario: a salaried borrower consolidating two credit cards. */
    static final int AMOUNT_USD = 25_000;
    static final double APR_PERCENT = 18.9;
    static final int TERM_MONTHS = 36;
    static final String PURPOSE = "debt consolidation";
    static final String EMPLOYMENT = "salaried";

    private final Map<String, Application> byCase = new ConcurrentHashMap<>();
    private final Map<String, Application> byApplication = new ConcurrentHashMap<>();

    /** A new application for a case: a fresh application id, the same borrower figures. */
    public Application open(String caseId) {
        return register(new Application(caseId, newId(), AMOUNT_USD, APR_PERCENT, TERM_MONTHS, PURPOSE, EMPLOYMENT, 7_400, 1_850, 712, 4,
                new Deposit(8_000, "2026-03-03", "the sale of the borrower's car", "a vehicle sale", "the bill of sale"),
                new EmploymentGap(92, "June to September 2025", "parental leave with the same employer", "the employer's letter confirming the leave")));
    }

    /**
     * A later application in the same case, from a different borrower: a large deposit of the same kind (a vehicle sale,
     * evidenced by a bill of sale) and only a short break between jobs.
     */
    public Application openNext(String caseId) {
        return register(new Application(caseId, newId(), 15_000, APR_PERCENT, TERM_MONTHS, "a home repair", EMPLOYMENT, 6_200, 1_400, 731, 5,
                new Deposit(6_500, "2026-04-14", "the sale of the borrower's motorcycle", "a vehicle sale", "the bill of sale"),
                new EmploymentGap(12, "January 2026", "a move between two salaried jobs", "the new employer's offer letter")));
    }

    private static String newId() {
        return "APP-" + ThreadLocalRandom.current().nextInt(10_000, 100_000);
    }

    private Application register(Application app) {
        byCase.put(app.caseId(), app);
        byApplication.put(app.applicationId(), app);
        return app;
    }

    public Application forCase(String caseId) {
        Application app = byCase.get(caseId);
        if (app == null) throw new IllegalStateException("No loan application opened for case " + caseId);
        return app;
    }

    public Optional<Application> forApplication(String applicationId) {
        return Optional.ofNullable(byApplication.get(applicationId));
    }
}
