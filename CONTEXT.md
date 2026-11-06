# Battery Debugger

An Android app that records a single phone's battery behaviour over days so that unexplained drain and untrustworthy battery percentages can be diagnosed afterwards from an exported log.

## Language

### Symptoms

**Drain**:
Battery charge lost over time, faster than expected for how the phone was used.
_Avoid_: Battery issue, battery problem

**Sudden Drop**:
A large fall in the Displayed Level over a short window that the measured charge lost does not account for.
_Avoid_: Crash, jump, glitch

### Measurements

**Displayed Level**:
The battery percentage the user sees in the phone's UI; on Pixel a smoothed, curve-mapped value derived from the Gauge Level.
_Avoid_: Battery level, percentage, SOC, UI SOC

**Gauge Level**:
The fuel gauge chip's own estimate of remaining charge as a percentage, before any smoothing for display.
_Avoid_: Raw SOC, real level, true level

**Level Estimate**:
Any percentage computed by the app from a signal other than the Displayed Level, used to check the Displayed Level against.
_Avoid_: Alternative level, second opinion, real percentage

**Learned Full Charge**:
The charge counter reading at the most recent moment the Displayed Level reached 100%; the "full" that charge-based Level Estimates are measured against.
_Avoid_: Max capacity, full capacity

**Design Capacity**:
The manufacturer's rated charge for the Subject Phone's battery when new.
_Avoid_: Nominal capacity, rated capacity

**Discharge Prediction**:
The system's own estimate of how long the battery will last before empty, as shown to the user.
_Avoid_: Time remaining, battery estimate

**Divergence**:
The difference between the Displayed Level and a given Level Estimate at the same moment.
_Avoid_: Error, drift, offset

### Recording

**Recording Gap**:
A period when the app was deliberately stopped from recording while the Subject Phone stayed on; distinct from the phone being off.
_Avoid_: Pause, outage, missing data

### Roles

**Subject Phone**:
The one phone being diagnosed; the app runs here.
_Avoid_: Device, target

**Analyst**:
The person who reads the exported log and makes the diagnosis; not the phone's owner.
_Avoid_: Developer, user
