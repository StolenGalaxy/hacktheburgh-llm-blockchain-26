// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

interface PaymentSystem {
    function transfer(address to, uint256 amount ) external returns (bool);
    function transferFrom(address from, address to, uint256 amount) external returns (bool);
    function balanceOf(address account) external view returns (uint256);
}

contract ProjectDeposit {
    address public immutable owner;
    PaymentSystem public immutable usdc;

    mapping(uint256 => uint256) public totalDeposited;
    mapping(uint256 => bool) public isSettled;
    mapping(uint256 => mapping(address => uint256)) public deposits;

    event Deposit(uint256 indexed poolId, address indexed depositor, uint256 amount);
    event Settled(uint256 indexed poolId, address[] recipients, uint256[] amounts);

    modifier onlyOwner() {
        require(msg.sender == owner, "Only oracle backend can execute this");
        _;
    }

    constructor(address _usdcAddress) {
        owner = msg.sender;
        usdc = PaymentSystem(_usdcAddress);
    }
}