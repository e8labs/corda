package net.corda.core.contracts

import net.corda.core.node.ServiceHub
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.LedgerTransaction

/**
 * A [StatePointer] contains a [pointer] to a [ContractState]. The [StatePointer] can be included in a [ContractState]
 * or included in an off-ledger data structure. [StatePointer]s can be resolved to a [StateAndRef] by performing a
 * vault query. There are two types of pointers; linear and static. [LinearPointer]s are for use with [LinearState]s.
 * [StaticPointer]s are for use with any type of [ContractState].
 */
@CordaSerializable
sealed class StatePointer<T : ContractState> {
    /**
     * An identifier for the [ContractState] that this [StatePointer] points to.
     */
    abstract val pointer: Any

    /**
     * Type of the state which is being pointed to.
     */
    abstract val type: Class<T>

    /**
     * Resolves a [StatePointer] to a [StateAndRef] via a vault query. This method will either return a [StateAndRef]
     * or return an exception.
     *
     * @param services a [ServiceHub] implementation is required to resolve the pointer.
     */
    abstract fun resolve(services: ServiceHub): StateAndRef<T>

    /**
     * Resolves a [StatePointer] to a [StateAndRef] from inside a [LedgerTransaction]. The intuition here is that all
     * of the pointed-to states will be included in the transaction as reference states.
     *
     * @param ltx the [LedgerTransaction] containing the [pointer] and pointed-to states.
     */
    abstract fun resolve(ltx: LedgerTransaction): StateAndRef<T>
}

/**
 * A [StaticPointer] contains a [pointer] to a specific [StateRef] and can be resolved by looking up the [StateRef] via
 * [ServiceHub]. There are a number of things to keep in mind when using [StaticPointer]s:
 * - The [ContractState] being pointed to may be spent or unspent when the [pointer] is resolved
 * - The [ContractState] may not be known by the node performing the look-up in which case the [resolve] method will
 *   throw a [TransactionResolutionException]
 */
class StaticPointer<T : ContractState>(override val pointer: StateRef, override val type: Class<T>) : StatePointer<T>() {
    /**
     * Resolves a [StaticPointer] to a [StateAndRef] via a [StateRef] look-up.
     */
    @Throws(TransactionResolutionException::class)
    @Suppress("UNCHECKED_CAST")
    override fun resolve(services: ServiceHub): StateAndRef<T> {
        val transactionState = services.loadState(pointer) as TransactionState<T>
        return StateAndRef(transactionState, pointer)
    }

    override fun resolve(ltx: LedgerTransaction): StateAndRef<T> {
        return ltx.referenceInputRefsOfType(type).single { pointer == it.ref }
    }
}

/**
 * [LinearPointer] allows a [ContractState] to "point" to another [LinearState] creating a "many-to-one" relationship
 * between all the states containing the pointer to a particular [LinearState] and the [LinearState] being pointed to.
 * Using the [LinearPointer] is useful when one state depends on the data contained within another state that evolves
 * independently. When using [LinearPointer] it is worth noting:
 * - The node performing the resolution may not have seen any [LinearState]s with the specified [linearId], as such the
 *   vault query in [resolve] will return null and [resolve] will throw an exception
 * - The node performing the resolution may not have the latest version of the [LinearState] and therefore will return
 *   an older version of the [LinearState]
 */
class LinearPointer<T : LinearState>(override val pointer: UniqueIdentifier, override val type: Class<T>) : StatePointer<T>() {
    /**
     * Resolves a [LinearPointer] using the [UniqueIdentifier] contained in the [pointer] property. Returns a
     * [StateAndRef] containing the latest version of the [LinearState] that the node calling [resolve] is aware of.
     *
     * @param services a [ServiceHub] implementation is required to perform a vault query.
     */
    @Suppress("UNCHECKED_CAST")
    override fun resolve(services: ServiceHub): StateAndRef<T> {
        // Return all versions of the LinearState over time.
        // TODO: Add paging as chains of provenance for token types could be quite long!
        val query = QueryCriteria.LinearStateQueryCriteria(
                linearId = listOf(pointer),
                status = Vault.StateStatus.ALL,
                relevancyStatus = Vault.RelevancyStatus.ALL
        )
        val result: Vault.Page<LinearState> = services.vaultService.queryBy(query)

        val stateAndRef = when {
            // The pointed-to state has not been seen by the resolver node. It is unlikely that this is the case.
            // The state can probably be obtained via subscribing to the data distribution group which created and
            // and maintains this data.
            result.states.isEmpty() ->
                throw IllegalStateException("The LinearState with ID ${pointer.id} is unknown to this node.")
            // Uh oh... The pointed-to state has been exited from the ledger!
            // It is unlikely this would ever happen as most reference data states will be created such that they cannot
            // be exited from the ledger. At this point there are two options; use an old consumed version of the state,
            // or don't use it at all.
            result.statesMetadata.all { it.status == Vault.StateStatus.CONSUMED } ->
                throw IllegalStateException("The LinearState with ID ${pointer.id} has been exited from the ledger.")
            // Otherwise, there should be one UNCONSUMED version of the state, so return it.
            else -> {
                val unconsumedStateRef = result.statesMetadata.single { it.status == Vault.StateStatus.UNCONSUMED }.ref
                result.states.single { it.ref == unconsumedStateRef }
            }
        }

        val transactionState = stateAndRef.state as TransactionState<T>
        return StateAndRef(transactionState, stateAndRef.ref)
    }

    override fun resolve(ltx: LedgerTransaction): StateAndRef<T> {
        return ltx.referenceInputRefsOfType(type).single { pointer == it.state.data.linearId }
    }
}