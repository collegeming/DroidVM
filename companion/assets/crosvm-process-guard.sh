# Read-only host /proc inspection. The caller sets DROIDVM_ALLOWED_PID to a
# freshly queried running target's PID, or 0 for a stopped target.
# Only fixed status words leave this script; no command line is read or logged.
fail_read() { printf UNREADABLE; exit 0; }
allowed=${DROIDVM_ALLOWED_PID:-0}
case "$allowed" in ''|*[!0-9]*) fail_read ;; esac
[ -x /system/bin/readlink ] || fail_read
[ "$allowed" -eq 0 ] || [ -d "/proc/$allowed" ] || fail_read
for proc in /proc/[0-9]*; do
    [ -d "$proc" ] || continue
    if ! IFS= read -r comm < "$proc/comm"; then
        [ -d "$proc" ] && fail_read
        continue
    fi
    if ! exe=$(/system/bin/readlink "$proc/exe" 2>/dev/null); then
        [ -d "$proc" ] || continue
        if ! IFS= read -r stat < "$proc/stat"; then
            [ -d "$proc" ] && fail_read
            continue
        fi
        rest=${stat##*) }
        set -- $rest
        state=$1
        flags=$7
        case "$flags" in ''|*[!0-9]*) fail_read ;; esac
        # Only zombies and PF_KTHREAD legitimately lack an executable. Do not
        # silently overlook a live userspace process whose identity is unreadable.
        [ "$state" = Z ] || [ $((flags & 2097152)) -ne 0 ] || fail_read
        continue
    fi
    case "$comm:${exe##*/}" in *crosvm*) ;; *) continue ;; esac
    [ -d "$proc" ] || continue
    [ "$allowed" -ne 0 ] || { printf BUSY; exit 0; }
    pid=${proc##*/}
    steps=0
    while [ "$pid" -ne "$allowed" ] && [ "$pid" -gt 1 ]; do
        [ "$steps" -lt 128 ] || fail_read
        if ! IFS= read -r stat < "/proc/$pid/stat"; then
            [ -d "$proc" ] && fail_read
            pid=0
            break
        fi
        # Strip through the last ')' since comm may contain spaces/parentheses.
        rest=${stat##*) }
        set -- $rest
        parent=$2
        case "$parent" in ''|*[!0-9]*) fail_read ;; esac
        [ "$parent" -ne "$pid" ] || fail_read
        pid=$parent
        steps=$((steps + 1))
    done
    [ "$pid" -eq "$allowed" ] || [ ! -d "$proc" ] || { printf BUSY; exit 0; }
done
printf CLEAR
