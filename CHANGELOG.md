# Unreleased

## Added

## Changed

# 0.2.2 (2021-11-17)

## Changed
- Switched to https://github.com/ingesolvoll/re-statecharts for statecharts integration. Core API should mostly be
intact, but `::discard`, `::stop`, `::state-full` have been removed in favor of their twins in re-statecharts.
  
## Added
- Much smoother deploy process, controlled through tags on github.

# 0.2.1 (2021-10-19)

## Changed
- Eased up some schemas. No breaking change.

# 0.2.0 (2021-10-18)

## Added
- Factory function for creating an HTTP FSM to embed within another FSM.

## Changed
- Minor but breaking changes in API and data structure. See docs if you get errors.

# 0.1.0 (2021-10-11 / 1e1ef8d)

- Initial release
