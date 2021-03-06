package net.corda.samples.obligation.contracts;

import net.corda.core.contracts.*;

import static net.corda.core.contracts.ContractsDSL.requireSingleCommand;
import static net.corda.core.contracts.ContractsDSL.requireThat;

import net.corda.core.identity.AbstractParty;
import net.corda.core.identity.Party;
import net.corda.core.transactions.LedgerTransaction;
import net.corda.samples.obligation.states.IOUState;

import java.security.PublicKey;
import java.util.*;
import java.util.stream.Collectors;

/**
 * This is the contracts code which defines how the [IOUState] behaves. Looks at the unit tests in
 * [IOUContractTests] for more insight on how this contracts verifies a transaction.
 */

// LegalProseReference: this is just a dummy string for the time being.

@LegalProseReference(uri = "<prose_contract_uri>")
public class IOUContract implements Contract {
    // Used to identify our contracts when building a transaction
    public static final String IOU_CONTRACT_ID = "net.corda.samples.obligation.contracts.IOUContract";

    /**
     * The IOUContract can handle three transaction types involving [IOUState]s.
     * - Issuance: Issuing a new [IOUState] on the ledger, which is a bilateral agreement between two parties.
     * - Transfer: Re-assigning the lender/beneficiary.
     * - Settle: Fully or partially settling the [IOUState].
     */
    //Used to indicate the transactions intent
    public interface Commands extends CommandData {
        class Issue extends TypeOnlyCommandData implements Commands {
        }

        class Transfer extends TypeOnlyCommandData implements Commands {
        }

        class Settle extends TypeOnlyCommandData implements Commands {
        }
    }

    /**
     * The contracts code for the [IOUContract].
     * The constraints are self documenting so don't require any additional explanation.
     */
    @Override
    public void verify(LedgerTransaction tx) {

        // We can use the requireSingleCommand function to extract command data from transaction.
        final CommandWithParties<Commands> command = requireSingleCommand(tx.getCommands(), Commands.class);
        final Commands commandData = command.getValue();

        /**
         * This command data can then be used inside of a conditional statement to indicate which set of tests we
         * should be performing - we will use different assertions to enable the contracts to verify the transaction
         * for issuing, settling and transferring.
         */
        if (commandData instanceof Commands.Issue)
            verifyIssue(tx);

        else if (commandData instanceof Commands.Transfer)
            verifyTransfer(tx);

        else if (commandData instanceof Commands.Settle)
            verifySettle(tx);

        else
            throw new IllegalArgumentException("Invalid Command");

    }


    private void verifyIssue(LedgerTransaction tx) {
        requireThat(require -> {

            require.using("No inputs should be consumed when issuing an IOU.", tx.getInputStates().size() == 0);
            require.using("Only one output states should be created when issuing an IOU.", tx.getOutputStates().size() == 1);

            IOUState outputState = tx.outputsOfType(IOUState.class).get(0);
            require.using("A newly issued IOU must have a positive amount.", outputState.getAmount() > 0);
            require.using("The lender and borrower cannot have the same identity.", outputState.getLender().getOwningKey() != outputState.getBorrower().getOwningKey());

            List<PublicKey> signers = tx.getCommand(0).getSigners();
            HashSet<PublicKey> signersSet = new HashSet<>();
            for (PublicKey key : signers) {
                signersSet.add(key);
            }

            List<AbstractParty> participants = tx.getOutputStates().get(0).getParticipants();
            HashSet<PublicKey> participantKeys = new HashSet<>();
            for (AbstractParty party : participants) {
                participantKeys.add(party.getOwningKey());
            }

            require.using("Both lender and borrower together only may sign IOU issue transaction.", signersSet.containsAll(participantKeys) && signersSet.size() == 2);

            return null;
        });

    }

    private void verifyTransfer(LedgerTransaction tx) {
        requireThat(require -> {

            require.using("An IOU transfer transaction should only consume one input states.", tx.getInputStates().size() == 1);
            require.using("An IOU transfer transaction should only create one output states.", tx.getOutputStates().size() == 1);

            // Copy of input with new lender;
            IOUState inputState = tx.inputsOfType(IOUState.class).get(0);
            IOUState outputState = tx.outputsOfType(IOUState.class).get(0);
            IOUState checkOutputState = new IOUState(outputState.getAmount(), inputState.getLender(), outputState.getBorrower(), outputState.getPaid(), outputState.getLinearId());

            require.using("Only the lender property may change.",
                    (checkOutputState.getAmount() == inputState.getAmount()) && checkOutputState.getLinearId().equals(inputState.getLinearId()) && checkOutputState.getBorrower().equals(inputState.getBorrower()) && (checkOutputState.getPaid() == inputState.getPaid()));
            require.using("The lender property must change in a transfer.", !outputState.getLender().getOwningKey().equals(inputState.getLender().getOwningKey()));

            Set<PublicKey> listOfParticipantPublicKeys = inputState.getParticipants().stream().map(AbstractParty::getOwningKey).collect(Collectors.toSet());
            listOfParticipantPublicKeys.add(outputState.getLender().getOwningKey());
            List<PublicKey> arrayOfSigners = tx.getCommand(0).getSigners();
            Set<PublicKey> setOfSigners = new HashSet<PublicKey>(arrayOfSigners);
            require.using("The borrower, old lender and new lender only must sign an IOU transfer transaction", setOfSigners.equals(listOfParticipantPublicKeys) && setOfSigners.size() == 3);
            return null;

        });

    }

    private void verifySettle(LedgerTransaction tx) {
        requireThat(require -> {

            // Check that only one input IOU should be consumed.
            require.using("One input IOU should be consumed when settling an IOU.", tx.getInputStates().size() == 1);

            IOUState inputIOU = tx.inputsOfType(IOUState.class).get(0);
            int inputAmount = inputIOU.getAmount();

            // Check if there is no more than 1 Output IOU state.
            require.using("No more than one output IOU should be created", tx.getOutputStates().size() <= 1);
            if (tx.getOutputStates().size() == 1) {
                // This means part amount of the obligation is settled.
                IOUState outputIOU = tx.outputsOfType(IOUState.class).get(0);
                require.using("Only the paid amount can change during part settlement.",
                        (outputIOU.getAmount() == inputAmount) && outputIOU.getLinearId().equals(inputIOU.getLinearId()) && outputIOU.getBorrower().equals(inputIOU.getBorrower()) && outputIOU.getLender().equals(inputIOU.getLender()));
                require.using("The paid amount must increase in case of part settlement of the IOU.", (outputIOU.getPaid() > inputIOU.getPaid()));
                require.using("The paid amount must be less than the total amount of the IOU", (outputIOU.getPaid() < inputIOU.getAmount()));
            }
            Set<PublicKey> listOfParticipantPublicKeys = inputIOU.getParticipants().stream().map(AbstractParty::getOwningKey).collect(Collectors.toSet());
            List<PublicKey> arrayOfSigners = tx.getCommand(0).getSigners();
            Set<PublicKey> setOfSigners = new HashSet<PublicKey>(arrayOfSigners);
            require.using("Both lender and borrower must sign IOU settle transaction.", setOfSigners.equals(listOfParticipantPublicKeys));

            return null;
        });

    }

}
