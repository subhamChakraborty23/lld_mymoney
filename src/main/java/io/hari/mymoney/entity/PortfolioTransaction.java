package io.hari.mymoney.entity;

import io.hari.mymoney.constant.PortfolioOperation;
import lombok.*;

import java.math.BigInteger;
import java.util.Optional;

/**
 * @Author hayadav
 * @create 5/8/2021
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortfolioTransaction {
    private PortfolioOperation operation;

    private Asset assets;

    private BigInteger total;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Asset {
        BigInteger equity;

        BigInteger dept;

        BigInteger gold;

        @Override
        public String toString() {
            return equity+" "+dept+" "+gold;
        }
    }

    public BigInteger updateTotal() {
        final BigInteger equity = Optional.ofNullable(assets.equity).orElseGet(() -> BigInteger.ZERO);
        final BigInteger dept = Optional.ofNullable(assets.dept).orElseGet(() -> BigInteger.ZERO);
        final BigInteger gold = Optional.ofNullable(assets.gold).orElseGet(() -> BigInteger.ZERO);
        this.total = equity.add(dept).add(gold);
        return total;
    }
}