package net.corda.docs

import com.google.common.util.concurrent.SettableFuture
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.getOrThrow
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.ServiceInfo
import net.corda.core.node.services.linearHeadsOfType
import net.corda.core.utilities.DUMMY_NOTARY
import net.corda.core.utilities.DUMMY_NOTARY_KEY
import net.corda.node.services.network.NetworkMapService
import net.corda.node.services.transactions.ValidatingNotaryService
import net.corda.node.utilities.databaseTransaction
import net.corda.testing.node.MockNetwork
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class WorkflowTransactionBuildTutorialTest {
    lateinit var net: MockNetwork
    lateinit var notaryNode: MockNetwork.MockNode
    lateinit var nodeA: MockNetwork.MockNode
    lateinit var nodeB: MockNetwork.MockNode

    // Helper method to locate the latest Vault version of a LinearState from a possibly out of date StateRef
    private inline fun <reified T : LinearState> ServiceHub.latest(ref: StateRef): StateAndRef<T> {
        val linearHeads = vaultService.linearHeadsOfType<T>()
        val original = storageService.validatedTransactions.getTransaction(ref.txhash)!!.tx.outRef<T>(ref.index)
        return linearHeads.get(original.state.data.linearId)!!
    }

    @Before
    fun setup() {
        net = MockNetwork(threadPerNode = true)
        notaryNode = net.createNode(
                legalName = DUMMY_NOTARY.name,
                keyPair = DUMMY_NOTARY_KEY,
                advertisedServices = *arrayOf(ServiceInfo(NetworkMapService.type), ServiceInfo(ValidatingNotaryService.type)))
        nodeA = net.createPartyNode(notaryNode.info.address)
        nodeB = net.createPartyNode(notaryNode.info.address)
        FxTransactionDemoTutorial.registerFxProtocols(nodeA.services)
        FxTransactionDemoTutorial.registerFxProtocols(nodeB.services)
        WorkflowTransactionBuildTutorial.registerWorkflowProtocols(nodeA.services)
        WorkflowTransactionBuildTutorial.registerWorkflowProtocols(nodeB.services)
    }

    @After
    fun cleanUp() {
        println("Close DB")
        net.stopNodes()
    }

    //@Test
    fun `Run workflow to completion`() {
        // Setup a vault subscriber to wait for successful upload of the proposal to NodeB
        val done1 = SettableFuture.create<Unit>()
        val subs1 = nodeB.services.vaultService.updates.subscribe {
            done1.set(Unit)
        }
        // Kick of the proposal flow
        val flow1 = nodeA.services.startFlow(SubmitTradeApprovalFlow("1234", nodeB.info.legalIdentity))
        // Wait for the flow to finish
        val proposalRef = flow1.resultFuture.getOrThrow()
        // Wait for NodeB to include it's copy in the vault
        done1.get()
        subs1.unsubscribe()
        // Fetch the latest copy of the state from both nodes
        val latestFromA = databaseTransaction(nodeA.database) {
            nodeA.services.latest<TradeApprovalContract.State>(proposalRef.ref)
        }
        val latestFromB = databaseTransaction(nodeB.database) {
            nodeB.services.latest<TradeApprovalContract.State>(proposalRef.ref)
        }
        // Confirm the state as as expected
        assertEquals(WorkflowState.NEW, proposalRef.state.data.state)
        assertEquals("1234", proposalRef.state.data.tradeId)
        assertEquals(nodeA.info.legalIdentity, proposalRef.state.data.source)
        assertEquals(nodeB.info.legalIdentity, proposalRef.state.data.counterparty)
        assertEquals(proposalRef, latestFromA)
        assertEquals(proposalRef, latestFromB)
        // Setup a vault subscriber to pause until the final update is in NodeA and NodeB
        val done2 = SettableFuture.create<Unit>()
        val subs2 = nodeA.services.vaultService.updates.subscribe {
            done2.set(Unit)
        }
        val done3 = SettableFuture.create<Unit>()
        val subs3 = nodeB.services.vaultService.updates.subscribe {
            done3.set(Unit)
        }
        // Run the manual completion flow from NodeB
        val flow2 = nodeB.services.startFlow(SubmitCompletionFlow(latestFromB.ref, WorkflowState.APPROVED))
        // wait for the flow to end
        val completedRef = flow2.resultFuture.getOrThrow()
        // wait for the vault updates to stabilise
        done2.get()
        done3.get()
        subs2.unsubscribe()
        subs3.unsubscribe()
        // Fetch the latest copies from the vault
        val finalFromA = databaseTransaction(nodeA.database) {
            nodeA.services.latest<TradeApprovalContract.State>(proposalRef.ref)
        }
        val finalFromB = databaseTransaction(nodeB.database) {
            nodeB.services.latest<TradeApprovalContract.State>(proposalRef.ref)
        }
        // Confirm the state is as expected
        assertEquals(WorkflowState.APPROVED, completedRef.state.data.state)
        assertEquals("1234", completedRef.state.data.tradeId)
        assertEquals(nodeA.info.legalIdentity, completedRef.state.data.source)
        assertEquals(nodeB.info.legalIdentity, completedRef.state.data.counterparty)
        assertEquals(completedRef, finalFromA)
        assertEquals(completedRef, finalFromB)
    }
}