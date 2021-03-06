package io.horizontalsystems.bankwallet.core.adapters

import io.horizontalsystems.bankwallet.core.AdapterState
import io.horizontalsystems.bankwallet.core.App
import io.horizontalsystems.bankwallet.core.AppLogger
import io.horizontalsystems.bankwallet.core.toHexString
import io.horizontalsystems.bankwallet.entities.TransactionRecord
import io.horizontalsystems.bankwallet.entities.TransactionType
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.hexStringToByteArray
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.FullTransaction
import io.reactivex.Flowable
import io.reactivex.Single
import java.math.BigDecimal
import java.math.BigInteger

class EthereumAdapter(kit: EthereumKit) : EthereumBaseAdapter(kit, decimal) {

    // IAdapter

    override fun start() {
        // started via EthereumKitManager
    }

    override fun stop() {
        // stopped via EthereumKitManager
    }

    override fun refresh() {
        // refreshed via EthereumKitManager
    }

    // IBalanceAdapter

    override val balanceState: AdapterState
        get() = convertToAdapterState(ethereumKit.syncState)

    override val balanceStateUpdatedFlowable: Flowable<Unit>
        get() = ethereumKit.syncStateFlowable.map {}

    override fun sendInternal(address: Address, amount: BigInteger, gasPrice: Long, gasLimit: Long, logger: AppLogger): Single<Unit> {
        return ethereumKit.send(address, amount, byteArrayOf(), gasPrice, gasLimit)
                .doOnSubscribe {
                    logger.info("call ethereumKit.send")
                }
                .map { }
    }

    override fun estimateGasLimitInternal(toAddress: Address?, value: BigInteger, gasPrice: Long?): Single<Long> {
        return ethereumKit.estimateGas(toAddress, value, gasPrice)
    }

    override val balance: BigDecimal
        get() = balanceInBigDecimal(ethereumKit.accountState?.balance, decimal)

    override val minimumRequiredBalance: BigDecimal
        get() = BigDecimal.ZERO

    override val minimumSendAmount: BigDecimal
        get() = BigDecimal.ZERO

    override val balanceUpdatedFlowable: Flowable<Unit>
        get() = ethereumKit.accountStateFlowable.map { }

    // ITransactionsAdapter

    override val transactionsState: AdapterState
        get() = convertToAdapterState(ethereumKit.transactionsSyncState)

    override val transactionsStateUpdatedFlowable: Flowable<Unit>
        get() = ethereumKit.transactionsSyncStateFlowable.map {}

    override fun getTransactions(from: TransactionRecord?, limit: Int): Single<List<TransactionRecord>> {
        return ethereumKit.etherTransactions(from?.transactionHash?.hexStringToByteArray(), limit).map {
            it.map { tx -> transactionRecord(tx) }
        }
    }

    override val transactionRecordsFlowable: Flowable<List<TransactionRecord>>
        get() = ethereumKit.etherTransactionsFlowable.map { it.map { tx -> transactionRecord(tx) } }

    private fun convertToAdapterState(syncState: EthereumKit.SyncState): AdapterState = when (syncState) {
        is EthereumKit.SyncState.Synced -> AdapterState.Synced
        is EthereumKit.SyncState.NotSynced -> AdapterState.NotSynced(syncState.error)
        is EthereumKit.SyncState.Syncing -> AdapterState.Syncing(50, null)
    }

    private fun transactionRecord(fullTransaction: FullTransaction): TransactionRecord {
        val transaction = fullTransaction.transaction
        val receipt = fullTransaction.receiptWithLogs?.receipt

        var fromAddress = transaction.from
        var toAddress = transaction.to
        val myAddress = ethereumKit.receiveAddress
        val fromMine = fromAddress == myAddress
        val toMine = toAddress == myAddress
        val fee = receipt?.gasUsed?.toBigDecimal()?.multiply(transaction.gasPrice.toBigDecimal())?.let { scaleDown(it) }

        var amount = if (fromMine) transaction.value.negate() else transaction.value
        fullTransaction.internalTransactions.forEach { internalTransaction ->
            var internalAmount = internalTransaction.value
            internalAmount = if (internalTransaction.from == myAddress) internalAmount.negate() else internalAmount
            amount += internalAmount
            fromAddress = internalTransaction.from
            toAddress = internalTransaction.to
        }
        val type = when {
            fromMine && toMine -> TransactionType.SentToSelf
            amount < BigInteger.ZERO -> TransactionType.Outgoing
            else -> TransactionType.Incoming
        }

        val txHashHex = transaction.hash.toHexString()
        return TransactionRecord(
                uid = txHashHex,
                transactionHash = txHashHex,
                transactionIndex = receipt?.transactionIndex ?: 0,
                interTransactionIndex = 0,
                blockHeight = receipt?.blockNumber,
                amount = scaleDown(amount.abs().toBigDecimal()),
                confirmationsThreshold = confirmationsThreshold,
                fee = fee,
                timestamp = transaction.timestamp,
                from = fromAddress.hex,
                memo = null,
                to = toAddress?.hex,
                type = type,
                failed = fullTransaction.isFailed()
        )
    }

    // ISendEthereumAdapter

    override val ethereumBalance: BigDecimal
        get() = balance

    override fun availableBalance(gasPrice: Long, gasLimit: Long): BigDecimal {
        return BigDecimal.ZERO.max(balance - fee(gasPrice, gasLimit))
    }


    companion object {
        const val decimal = 18

        fun clear(walletId: String, testMode: Boolean) {
            val networkType = if (testMode) EthereumKit.NetworkType.Ropsten else EthereumKit.NetworkType.MainNet
            EthereumKit.clear(App.instance, networkType, walletId)
        }
    }

}
