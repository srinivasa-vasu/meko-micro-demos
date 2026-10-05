# CARD-DISPUTE-07: Failed capture after successful authorization (dispute policy)

Fictional demo policy for the Meko GOAP demo. Not a banking, card-network, regulatory or legal policy.
Owner: Payments Operations. Applies to: payments specialists and agents handling card disputes.

## CARD-DISPUTE-07 policy: scope

Use this policy when the card authorization succeeded but the merchant capture timed out at the gateway and no
settled capture exists. The customer usually sees a pending or posted debit for a purchase that "never went through".

## CARD-DISPUTE-07 policy: handling

1. Treat the transaction as an unsettled payment incident, not as a chargeback.
2. Verify the settlement state first. Do not close the case before settlement is confirmed.
3. A provisional refund may be approved while settlement is verified, when the customer-facing debit is present and no
   settled capture exists.
4. Do not conclude "duplicate charge" just because two gateway messages exist: compare the authorization id and the
   capture id.
5. Escalate to a human payments specialist when evidence is unavailable or conflicting.
