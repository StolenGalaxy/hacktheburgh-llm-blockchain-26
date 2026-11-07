// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

interface PaymentSystem {
    function transfer(address to, uint256 amount) external returns (bool);
    function transferFrom(address from, address to, uint256 amount) external returns (bool);
    function balanceOf(address account) external view returns (uint256);
}

/// @notice Holds deposits until the trusted owner settles each pool, then lets recipients claim their allocations.
contract ProjectDeposit {
    address public immutable owner;
    PaymentSystem public immutable usdc;

    mapping(uint256 => uint256) public totalDeposited;
    mapping(uint256 => bool) public isSettled;
    mapping(uint256 => mapping(address => uint256)) public deposits;
    mapping(uint256 => mapping(address => uint256)) public claimable;

    bool private entered;

    event Deposit(uint256 indexed poolId, address indexed depositor, uint256 amount);
    event Settled(uint256 indexed poolId, address[] recipients, uint256[] amounts);
    event Withdrawn(uint256 indexed poolId, address indexed recipient, uint256 amount);

    modifier onlyOwner() {
        require(msg.sender == owner, "Only oracle backend can execute this");
        _;
    }

    modifier nonReentrant() {
        require(!entered, "Reentrant call");
        entered = true;
        _;
        entered = false;
    }

    constructor(address _usdcAddress) {
        require(_usdcAddress != address(0) && _usdcAddress.code.length > 0, "Invalid USDC address");
        owner = msg.sender;
        usdc = PaymentSystem(_usdcAddress);
    }

    function deposit(uint256 poolId, uint256 amount) external nonReentrant {
        require(!isSettled[poolId], "Pool already settled");
        require(amount > 0, "Deposit must be positive");

        uint256 balanceBefore = usdc.balanceOf(address(this));
        require(usdc.transferFrom(msg.sender, address(this), amount), "USDC transferFrom failed");
        uint256 balanceAfter = usdc.balanceOf(address(this));
        require(balanceAfter >= balanceBefore && balanceAfter - balanceBefore == amount, "Unsupported token transfer");

        deposits[poolId][msg.sender] += amount;
        totalDeposited[poolId] += amount;
        emit Deposit(poolId, msg.sender, amount);
    }

    /// @notice The owner is trusted to determine the pool outcome; allocations must distribute the entire pool.
    function settlePool(uint256 poolId, address[] calldata recipients, uint256[] calldata amounts) external onlyOwner {
        require(!isSettled[poolId], "Pool already settled");
        require(totalDeposited[poolId] > 0, "Pool has no deposits");
        require(recipients.length > 0 && recipients.length == amounts.length, "Invalid allocation arrays");

        uint256 allocationTotal;
        for (uint256 i = 0; i < recipients.length; i++) {
            require(recipients[i] != address(0), "Invalid recipient");
            allocationTotal += amounts[i];
        }
        require(allocationTotal == totalDeposited[poolId], "Allocations must equal deposits");

        isSettled[poolId] = true;
        for (uint256 i = 0; i < recipients.length; i++) {
            claimable[poolId][recipients[i]] += amounts[i];
        }
        emit Settled(poolId, recipients, amounts);
    }

    /// @notice Recipients pull their settlement, so one recipient cannot block other withdrawals.
    function withdraw(uint256 poolId) external nonReentrant {
        require(isSettled[poolId], "Pool is not settled");
        uint256 amount = claimable[poolId][msg.sender];
        require(amount > 0, "Nothing to withdraw");

        claimable[poolId][msg.sender] = 0;
        require(usdc.transfer(msg.sender, amount), "USDC transfer failed");
        emit Withdrawn(poolId, msg.sender, amount);
    }
}
