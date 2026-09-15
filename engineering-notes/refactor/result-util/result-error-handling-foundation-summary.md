# Result / Error-Handling Foundation Summary

## Branch

- `ai/codex/refactor/result-util-foundation`

## What Changed

- Refactored [`Result.java`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/result/Result.java) from a public record into a `final` class with a private constructor.
- Added static factory methods on `Result`:
  - `success(...)`
  - `failure(...)`
  - `from(...)`
- Added constructor invariants in `Result` to reject malformed states:
  - success result with non-null `errorCode`
  - failed result with null `errorCode`
  - failed result with non-null `value`
- Preserved value semantics on `Result` by implementing `equals`, `hashCode`, and `toString`.
- Added compatibility aliases to [`IResult.java`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/result/IResult.java):
  - `isSuccess()`
  - `getErrorCode()`
  - `getValue()`
  - `getDetail()`
  - `getScope()`
- Completed [`ProtoErrorMapper.java`](D:/TestProgram/Java/JavaEE/z-exchange/common-util/src/main/java/com/exchange/common/result/error/ProtoErrorMapper.java) with `ErrorCategory -> ErrorCodePb` mapping and an `ErrorCode` convenience overload.

## Scope Kept Intentionally Narrow

- Did not touch ledger or wallet boundary code.
- Did not migrate any active callers from `com.exchange.common.utils.result` yet.
- Did not complete subsystem enum rollout such as `IdempErrorCode`.
- Did not remove old proto-coupled types yet.

## Remaining Follow-Up

- Wire service boundary builders to `ProtoErrorMapper`.
- Finish subsystem error enums and migrate `CommonErrorCode` callers gradually.
- Decide whether proto mapping should remain in `common-util` or move to service-local boundary mappers for the final architecture.
- Add validator and contract tests from the checklist.

## Verification

- Attempted: `mvn -pl common-util -am -DskipTests compile`
- Result: blocked by local toolchain configuration, not by this patch
- Failure: `invalid target release: 21`
