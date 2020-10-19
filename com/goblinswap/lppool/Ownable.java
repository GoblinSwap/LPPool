package com.goblinswap.lppool;

import io.nuls.contract.sdk.Address;
import io.nuls.contract.sdk.Msg;

import static io.nuls.contract.sdk.Utils.require;

public class Ownable {
    /**
     * 合约创建者
     */
    protected Address contractCreator;

    protected Address owner;

    public Ownable() {
        this.owner = Msg.sender();
        this.contractCreator = this.owner;
    }

    public Address viewOwner() {
        return owner;
    }


    protected void onlyOwner() {
        require(Msg.sender().equals(owner), "Only the owner of the contract can execute it.");
    }
}
