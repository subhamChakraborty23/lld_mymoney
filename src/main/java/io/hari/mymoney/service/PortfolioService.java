package io.hari.mymoney.service;

import io.hari.mymoney.config.AppConfig;
import io.hari.mymoney.constant.ActionType;
import io.hari.mymoney.constant.Month;
import io.hari.mymoney.entity.Portfolio;
import io.hari.mymoney.entity.PortfolioTransaction;
import io.hari.mymoney.entity.input.UserOperation;
import io.hari.mymoney.entity.input.UserOperationAllocate;
import io.hari.mymoney.entity.input.UserOperationChange;
import io.hari.mymoney.entity.input.UserOperationSIP;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.hari.mymoney.constant.ConstantUtil.CANNOT_RE_BALANCE;
import static io.hari.mymoney.constant.ConstantUtil.NO_BALANCE;

/**
 * @Author hayadav
 * @create 5/8/2021
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PortfolioService {
    private final AppConfig config;

    /**
     * Approach:
     * get month object -> fetch after_market_change
     */
    public String getBalanceOperation(@NonNull final Portfolio portfolio, @NonNull final Month month) {
        log.info("getBalanceOperation :{}", month.name().toUpperCase());
        AtomicReference<String> result = new AtomicReference<>(NO_BALANCE);

        final Map<Month, Portfolio.PortfolioOperation> portfolioOperations = portfolio.getPortfolioOperations();
        final List<PortfolioTransaction> fetchMonthTransactions = Optional.ofNullable(portfolioOperations.get(month))
                .orElseGet(() -> new Portfolio.PortfolioOperation()).getPortfolioTransactions();
        final Optional<PortfolioTransaction> transaction = fetchMonthTransactions.stream().filter(Objects::nonNull)
                .filter(i -> i.getOperation().equals(io.hari.mymoney.constant.PortfolioOperation.after_market_change))
                .collect(Collectors.toList()).stream().findFirst();
        transaction.ifPresent(afterMarketChangeTxn -> result.set(afterMarketChangeTxn.getAssets().toString()));
        return result.get();
    }

    public String getReBalanceOperation(@NonNull final Portfolio portfolio) {
        log.info("getReBalanceOperation called");
        final Map<Month, Portfolio.PortfolioOperation> portfolioOperations = portfolio.getPortfolioOperations();

        List<Month> list = new LinkedList<>(portfolioOperations.keySet());
        if (list.size() < config.getReBalanceLimit()) return CANNOT_RE_BALANCE;

        AtomicReference<String> result = new AtomicReference<>(CANNOT_RE_BALANCE);

        final Month lastMonthObject = list.get(list.size() - 1);
        final Portfolio.PortfolioOperation portfolioOperation = Optional.ofNullable(portfolioOperations.get(lastMonthObject))
                .orElseGet(() -> new Portfolio.PortfolioOperation());

        final List<PortfolioTransaction> portfolioTransactions = Optional.ofNullable((portfolioOperation.getPortfolioTransactions()))
                .orElseGet(() -> new LinkedList<>());

        final Optional<PortfolioTransaction> lastMonthTransaction = portfolioTransactions.stream()
                .filter(i -> i.getOperation().equals(io.hari.mymoney.constant.PortfolioOperation.after_market_change)).findFirst();

        lastMonthTransaction.ifPresent(afterMarketChangeTxn -> {
            final PortfolioTransaction newTransaction = newPortfolioTransaction(portfolio, afterMarketChangeTxn);
            portfolioTransactions.add(newTransaction);
            result.set(newTransaction.getAssets().toString());
        });
        return result.get();
    }

    private PortfolioTransaction newPortfolioTransaction(final Portfolio portfolio, final PortfolioTransaction lastMonthTransaction) {
        final BigInteger total = lastMonthTransaction.getTotal();
        final PortfolioTransaction newTransaction = PortfolioTransaction.builder().operation(io.hari.mymoney.constant.PortfolioOperation.re_balance)
                .assets(PortfolioTransaction.Asset.builder()
                        .equity(getAssetValue(portfolio.getInitialEquityPercent(), total))
                        .dept(getAssetValue(portfolio.getInitialDeptPercent(), total))
                        .gold(getAssetValue(portfolio.getInitialGoldPercent(), total))
                        .build())
                .total(total)
                .build();
        return newTransaction;
    }

    private BigInteger getAssetValue(@NonNull final Double initialPercentage, @NonNull final BigInteger total) {
        final double percentage = initialPercentage / 100;
        final BigDecimal totalInDecimal = new BigDecimal(total);

        final BigDecimal result = totalInDecimal.multiply(BigDecimal.valueOf(percentage));//123.1231
        final String resultInString = String.valueOf(result.setScale(0, RoundingMode.FLOOR));//123

        return new BigInteger(resultInString);//123 in int
    }

    public void updatePortfolioInitialPercentage(@NonNull final Portfolio portfolio, @NonNull final List<UserOperation> userOperations) {
        userOperations.stream().filter(Objects::nonNull).filter(i -> i.getOperation().equals(ActionType.allocate)).findFirst()
                .ifPresent(operationAbstract -> {
                    final UserOperationAllocate operationAllocate = UserOperationAllocate.class.cast(operationAbstract);
                    updatePortfolioInitialPercentage(portfolio, operationAllocate);
                });
    }

    public void updatePortfolioInitialPercentage(@NonNull final Portfolio portfolio, @NonNull final UserOperationAllocate operationAllocate) {
        final BigInteger total = operationAllocate.getEquity()
                .add(operationAllocate.getDept())
                .add(operationAllocate.getGold());
        portfolio.setInitialEquityPercent(getAssetPercentageValue(total, operationAllocate.getEquity()));
        portfolio.setInitialDeptPercent(getAssetPercentageValue(total, operationAllocate.getDept()));
        portfolio.setInitialGoldPercent(getAssetPercentageValue(total, operationAllocate.getGold()));
    }

    private double getAssetPercentageValue(@NonNull final BigInteger total, @NonNull final BigInteger fetchValue) {
        final BigDecimal bigDecimalTotal = new BigDecimal(total);
        final BigDecimal bigDecimalFetched = new BigDecimal(fetchValue);
        final double multiply = bigDecimalFetched.doubleValue() / bigDecimalTotal.doubleValue();
        return multiply * Double.valueOf(100);
    }

    public void executeUserCHANGEOperation(@NonNull final Portfolio portfolio, @NonNull final List<UserOperation> abstracts) {
        final UserOperationAllocate operationAllocate = UserOperationAllocate.class.cast(fetchOperation(abstracts, ActionType.allocate));
        final UserOperationSIP operationSIP = UserOperationSIP.class.cast(fetchOperation(abstracts, ActionType.sip));

        final List<UserOperation> userOperations = abstracts.stream().filter(i -> i.getOperation().equals(ActionType.change)).collect(Collectors.toList());
        //create intial map and update start default value

        final Map<Month, Portfolio.PortfolioOperation> portfolioMap = new LinkedHashMap<>();

        final UserOperation userCHANGEOperation = userOperations.get(0);
        final UserOperationChange userOperationChange = UserOperationChange.class.cast(userCHANGEOperation);

        final PortfolioTransaction firstTransaction = newPortfolioTransaction(operationAllocate);
        List<PortfolioTransaction> transactions = new LinkedList<>();
        transactions.add(firstTransaction);

        final Portfolio.PortfolioOperation build = Portfolio.PortfolioOperation.builder().portfolioTransactions(transactions).build();
        portfolioMap.put(userOperationChange.getMonth(), build);

        AtomicInteger atomicInteger = new AtomicInteger(0);
        UserOperationAllocate operationAllocate_previous = operationAllocate;
        AtomicReference<PortfolioTransaction> previousTransaction = new AtomicReference<>(firstTransaction);

        userOperations.stream().forEach(operation -> {
            final UserOperationChange operationChange = UserOperationChange.class.cast(operation);
            //add previous as existing
            final PortfolioTransaction.Asset previousTransactionAsset = previousTransaction.get().getAssets();
            final PortfolioTransaction newTransaction = PortfolioTransaction.builder().operation(io.hari.mymoney.constant.PortfolioOperation.existing)
                    .build();
            newTransaction.setAssets(PortfolioTransaction.Asset.builder()
                    .equity(previousTransactionAsset.getEquity())
                    .dept(previousTransactionAsset.getDept())
                    .gold(previousTransactionAsset.getGold())
                    .build());
            newTransaction.updateTotal();

            portfolioMap.putIfAbsent(operationChange.getMonth(), new Portfolio.PortfolioOperation());
            portfolioMap.getOrDefault(operationChange.getMonth(), new Portfolio.PortfolioOperation()).getPortfolioTransactions().add(newTransaction);


            PortfolioTransaction.Asset previousAsset = previousTransaction.get().getAssets();
            if (atomicInteger.get() >= 1 && operationSIP != null) {
                updateSIPOperation(operationSIP, portfolioMap, previousTransaction, operationChange, previousAsset);
            }

            final PortfolioTransaction portfolioTransaction =
                    PortfolioTransaction.builder().operation(io.hari.mymoney.constant.PortfolioOperation.after_market_change).build();
            previousAsset = previousTransaction.get().getAssets();

            final BigInteger e = calculatePercentageBetween2Values_forAllocation(previousAsset.getEquity(), operationChange.getEquityPercent());
            final BigInteger d = calculatePercentageBetween2Values_forAllocation(previousAsset.getDept(), operationChange.getDeptPercent());
            final BigInteger g = calculatePercentageBetween2Values_forAllocation(previousAsset.getGold(), operationChange.getGoldPercent());

            portfolioTransaction.setAssets(PortfolioTransaction.Asset.builder().equity(e).dept(d).gold(g).build());
            portfolioTransaction.updateTotal();

            portfolioMap.putIfAbsent(operationChange.getMonth(), new Portfolio.PortfolioOperation());
            portfolioMap.get(operationChange.getMonth()).getPortfolioTransactions().add(portfolioTransaction);

            atomicInteger.getAndIncrement();
            previousTransaction.set(portfolioTransaction);
        });
        portfolio.setPortfolioOperations(portfolioMap);
    }

    private void updateSIPOperation(UserOperationSIP operationSIP, Map<Month, Portfolio.PortfolioOperation> portfolioMap, AtomicReference<PortfolioTransaction> previousTransaction, UserOperationChange operationChange, PortfolioTransaction.Asset previousAsset) {
        final BigInteger equityForSip = calculateEquityForSip(previousAsset.getEquity(), operationSIP.getEquity());
        final BigInteger deptForSip = calculateEquityForSip(previousAsset.getDept(), operationSIP.getDept());
        final BigInteger goldForSip = calculateEquityForSip(previousAsset.getGold(), operationSIP.getGold());

        final PortfolioTransaction transaction = PortfolioTransaction.builder().operation(io.hari.mymoney.constant.PortfolioOperation.after_sip)
                .build();
        transaction.setAssets(PortfolioTransaction.Asset.builder()
                .equity(equityForSip).dept(deptForSip).gold(goldForSip).build());
        transaction.updateTotal();

        portfolioMap.putIfAbsent(operationChange.getMonth(), new Portfolio.PortfolioOperation());
        portfolioMap.getOrDefault(operationChange.getMonth(), new Portfolio.PortfolioOperation()).getPortfolioTransactions().add(transaction);
        previousTransaction.set(transaction);
    }

    private BigInteger calculateEquityForSip(@NonNull final BigInteger previousValue, @NonNull final BigInteger sipValue) {
        return previousValue.add(sipValue);
    }

    private PortfolioTransaction newPortfolioTransaction(@NonNull final UserOperationAllocate allocate) {
        final PortfolioTransaction build1 = PortfolioTransaction.builder().operation(io.hari.mymoney.constant.PortfolioOperation.allocation)
                .assets(PortfolioTransaction.Asset.builder()
                        .equity(allocate.getEquity())
                        .dept(allocate.getDept())
                        .gold(allocate.getGold())
                        .build())
                .build();
        build1.updateTotal();
        return build1;
    }

    private UserOperation fetchOperation(@NonNull final List<UserOperation> abstracts, @NonNull final ActionType actionType) {
        final UserOperation allocate = abstracts.stream().filter(i -> i.getOperation().equals(actionType)).findFirst()
                .orElseThrow(() -> new RuntimeException("operation not found"));
        return allocate;
    }

    public BigInteger calculatePercentageBetween2Values_forAllocation(@NonNull final BigInteger asset, @NonNull final Double assetPercentage) {
        final BigDecimal hundred = BigDecimal.valueOf(100);
        final BigDecimal percent = BigDecimal.valueOf(assetPercentage).divide(hundred);

        final BigDecimal deAsset = new BigDecimal(asset);
        final BigDecimal multiply = deAsset.multiply(percent);
        final BigDecimal add = deAsset.add(multiply);
        final BigDecimal bigDecimal = add.setScale(0, RoundingMode.FLOOR);
        final BigInteger bigInteger = new BigInteger(String.valueOf(bigDecimal));
        return bigInteger;
    }
}