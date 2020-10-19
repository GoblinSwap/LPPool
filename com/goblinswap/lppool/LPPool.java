package com.goblinswap.lppool;

import io.nuls.contract.sdk.*;
import io.nuls.contract.sdk.annotation.View;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import static io.nuls.contract.sdk.Utils.emit;
import static io.nuls.contract.sdk.Utils.require;

public class LPPool extends LPTokenWrapper implements Contract {
    public Address iToken;

    public Address LPToken;

    public static long DURATION = 86400 * 20;
    public static long startTime = 0;

    public long periodFinish = 0;
    public BigInteger rewardRate = BigInteger.ZERO;
    public BigInteger totalReward = BigInteger.ZERO;

    public long lastUpdateTime;
    public BigInteger rewardPerTokenStored = BigInteger.ZERO;
    public Map<Address, BigInteger> userRewardPerTokenPaid = new HashMap<Address, BigInteger>();
    public Map<Address, BigInteger> rewards = new HashMap<Address, BigInteger>();
    public Map<Address, BigInteger> userAcceleration = new HashMap<Address, BigInteger>();
    private Map<Address,Boolean> allowedContracts = new HashMap<Address, Boolean>();
    public Address rewardDistribution;


    public LPPool(String address, String LPAddress, long startTime) {
        iToken = new Address(address);
        LPToken = new Address(LPAddress);
        this.startTime = startTime;
        rewardDistribution = Msg.sender();

    }

    private void onlyRewardDistribution() {
        require(Msg.sender().equals(rewardDistribution), "Caller is not reward distribution");

    }

    public void setRewardDistribution(Address _rewardDistribution) {
        onlyOwner();
        rewardDistribution = _rewardDistribution;
    }

    private void checkStart() {
        require(Block.timestamp() >= startTime, "not start");
    }

    private void updateReward(Address account) {
        rewardPerTokenStored = rewardPerToken();
        lastUpdateTime = lastTimeRewardApplicable();
        if (account != null) {
            rewards.put(account, _earned(account));
            userRewardPerTokenPaid.put(account, rewardPerTokenStored);
        }
    }

    public long lastTimeRewardApplicable() {
        long timestamp = Block.timestamp();
        return timestamp < periodFinish ? timestamp : periodFinish;
    }


    private BigInteger rewardPerToken() {
        if (_totalSupply().equals(BigInteger.ZERO)) {
            return rewardPerTokenStored;
        }

        return rewardPerTokenStored.
                add(BigInteger.valueOf(lastTimeRewardApplicable()).
                        subtract(BigInteger.valueOf(lastUpdateTime)).
                        multiply(rewardRate).
                        multiply(BigInteger.valueOf((long) 1e8)).
                        divide(_totalSupply()));
    }

    @View
    public BigInteger rewardPerSecond() {
        return rewardRate;
    }

    @View
    public BigInteger availableReward() {
        long timestamp = Block.timestamp();

        BigInteger available = BigInteger.ZERO;
        if ( timestamp <= startTime )
        {
            available = totalReward;
        }
        else
        {
            if ( timestamp < periodFinish )
            {
                available = rewardRate.multiply(BigInteger.valueOf(periodFinish - timestamp));
            }
        }

        return available;
    }

    @View
    public BigInteger earned(Address account) {
        return _earned(account);
    }

    private BigInteger _earned(Address account) {
        BigInteger userRewardPer = BigInteger.ZERO;
        if (userRewardPerTokenPaid.get(account) != null) {
            userRewardPer = userRewardPer.add(userRewardPerTokenPaid.get(account));
        }

        BigInteger reward = BigInteger.ZERO;
        if (rewards.get(account) != null) {
            reward = reward.add(rewards.get(account));
        }

        BigInteger acceleration = _getAcceleration(account);

        return _balanceOf(account).
                multiply(rewardPerToken().subtract(userRewardPer).multiply(acceleration).divide(BigInteger.valueOf((long) 1e8))).
                divide(BigInteger.valueOf((long) 1e8)).add(reward);

    }

    public void stake(BigInteger amount) {
        checkStart();
        require(amount.compareTo(BigInteger.ZERO) > 0, "Cannot stake 0");
        String[][] a = new String[][]{new String[]{Msg.sender().toString()}, new String[]{amount.toString()}};
        LPToken.call("lockLiquidity", null, a, null);

        updateReward(Msg.sender());
        super.stake(amount);
        emit(new Staked(Msg.sender(), amount));
    }

    public void withdraw(BigInteger amount) {
        checkStart();
        require(amount.compareTo(BigInteger.ZERO) > 0, "amount must > 0");
        require(amount.compareTo(_balanceOf(Msg.sender())) <= 0, "Cannot withdraw exceed the balance");
        String[][] a = new String[][]{new String[]{Msg.sender().toString()}, new String[]{amount.toString()}};
        LPToken.call("withdrawLiquidity", null, a, null);
        getReward();
        super.withdraw(amount);
        emit(new Withdrawn(Msg.sender(), amount));

    }

    private void getReward() {
        checkStart();
        BigInteger trueReward = _earned(Msg.sender());

        if (trueReward.compareTo(BigInteger.ZERO) > 0) {
            rewards.put(Msg.sender(), BigInteger.ZERO);
            String[][] a = new String[][]{new String[]{Msg.sender().toString()}, new String[]{trueReward.toString()}};
            iToken.call("mint", null, a, null);
            emit(new RewardPaid(Msg.sender(), trueReward));
            updateReward(null);
        }

    }

    public void notifyRewardAmount(BigInteger reward) {

        onlyRewardDistribution();
        updateReward(null);
        if (Block.timestamp() > startTime) {
            if (Block.timestamp() >= periodFinish) {
                rewardRate = reward.divide(BigInteger.valueOf(DURATION));
                totalReward = reward;
            } else {
                BigInteger remaining = BigInteger.valueOf(periodFinish).subtract(BigInteger.valueOf(Block.timestamp()));
                BigInteger leftover = remaining.multiply(rewardRate);
                rewardRate = reward.add(leftover).divide(BigInteger.valueOf(DURATION));
                totalReward = reward.add(leftover);
            }
            lastUpdateTime = Block.timestamp();
            periodFinish = Block.timestamp() + DURATION;
            emit(new RewardAdded(reward));
        } else {
            rewardRate = reward.divide(BigInteger.valueOf(DURATION));
            totalReward = reward;
            lastUpdateTime = startTime;
            periodFinish = startTime + DURATION;
            emit(new RewardAdded(reward));
        }

    }

    private BigInteger min(BigInteger a, BigInteger b) {
        return a.compareTo(b) < 0 ? a : b;
    }

    public void addAllowedContract(Address address){
        onlyOwner();
        require(address != null, "address can't be null");
        allowedContracts.put(address,true);
    }

    public void removeAllowedContract(Address address){
        onlyOwner();
        require(address != null, "address can't be null");
        allowedContracts.put(address,false);
    }

    public void miningAcceleration(Address account, BigInteger acceleration) {
        if(Msg.sender().equals(owner) || (allowedContracts.get(Msg.sender())!=null && allowedContracts.get(Msg.sender()))){
            require(account != null, "account can't be null");
            require(acceleration !=null && acceleration.compareTo(BigInteger.ZERO)>0 && acceleration.compareTo(BigInteger.valueOf(70000000))<=0 );

            if ( Block.timestamp() > startTime && userAcceleration.get(account) != null && userAcceleration.get(account).compareTo(acceleration) != 0 ) {
                rewardPerTokenStored = rewardPerToken();
                if (account != null) {
                    rewards.put(account, _earned(account));
                    userRewardPerTokenPaid.put(account, rewardPerTokenStored);
                }
            }

            userAcceleration.put(account, acceleration);
        }
    }

    @View
    public BigInteger getAcceleration(Address account) {
        return _getAcceleration(account);
    }

    private BigInteger _getAcceleration(Address account) {
        require(account != null, "account can't be null");
        
        BigInteger acceleration = BigInteger.valueOf(30000000);
        if (userAcceleration.get(account) != null) {
            acceleration = acceleration.add(userAcceleration.get(account));
        }

        return acceleration;
    }

    class Staked implements Event {
        private Address user;
        private BigInteger amount;

        public Staked(Address user, BigInteger amount) {
            this.user = user;
            this.amount = amount;
        }

        public Address getUser() {
            return user;
        }

        public void setUser(Address user) {
            this.user = user;
        }

        public BigInteger getAmount() {
            return amount;
        }

        public void setAmount(BigInteger amount) {
            this.amount = amount;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Staked that = (Staked) o;

            if (user != null ? !user.equals(that.user) : that.user != null) return false;
            return amount != null ? amount.equals(that.amount) : that.amount == null;
        }

        @Override
        public int hashCode() {
            int result = user != null ? user.hashCode() : 0;
            result = 31 * result + (amount != null ? amount.hashCode() : 0);
            return result;
        }


        @Override
        public String toString() {
            return "Staked{" +
                    "user=" + user +
                    ", amount=" + amount +
                    '}';
        }
    }


    class Withdrawn implements Event {
        private Address user;
        private BigInteger amount;

        public Withdrawn(Address user, BigInteger amount) {
            this.user = user;
            this.amount = amount;
        }

        public Address getUser() {
            return user;
        }

        public void setUser(Address user) {
            this.user = user;
        }

        public BigInteger getAmount() {
            return amount;
        }

        public void setAmount(BigInteger amount) {
            this.amount = amount;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Withdrawn that = (Withdrawn) o;

            if (user != null ? !user.equals(that.user) : that.user != null) return false;
            return amount != null ? amount.equals(that.amount) : that.amount == null;
        }

        @Override
        public int hashCode() {
            int result = user != null ? user.hashCode() : 0;
            result = 31 * result + (amount != null ? amount.hashCode() : 0);
            return result;
        }


        @Override
        public String toString() {
            return "Withdrawn{" +
                    "user=" + user +
                    ", amount=" + amount +
                    '}';
        }
    }

    class RewardPaid implements Event {
        private Address user;
        private BigInteger amount;

        public RewardPaid(Address user, BigInteger amount) {
            this.user = user;
            this.amount = amount;
        }

        public Address getUser() {
            return user;
        }

        public void setUser(Address user) {
            this.user = user;
        }

        public BigInteger getAmount() {
            return amount;
        }

        public void setAmount(BigInteger amount) {
            this.amount = amount;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            RewardPaid that = (RewardPaid) o;

            if (user != null ? !user.equals(that.user) : that.user != null) return false;
            return amount != null ? amount.equals(that.amount) : that.amount == null;
        }

        @Override
        public int hashCode() {
            int result = user != null ? user.hashCode() : 0;
            result = 31 * result + (amount != null ? amount.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "Withdrawn{" +
                    "user=" + user +
                    ", amount=" + amount +
                    '}';
        }
    }


    class RewardAdded implements Event {
        private BigInteger reward;

        public RewardAdded(BigInteger reward) {
            this.reward = reward;
        }

        public BigInteger getReward() {
            return reward;
        }

        public void setReward(BigInteger reward) {
            this.reward = reward;
        }

        @Override
        public String toString() {
            return "RewardAdded{" +
                    "reward=" + reward +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            RewardAdded that = (RewardAdded) o;

            return reward != null ? reward.equals(that.reward) : that.reward == null;
        }

        @Override
        public int hashCode() {
            int result = reward != null ? reward.hashCode() : 0;
            return result;
        }
    }

}
