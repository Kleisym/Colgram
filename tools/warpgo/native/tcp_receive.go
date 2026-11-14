package main

// Cumulative TCP receive sequence. Never expose or acknowledge bytes beyond a gap.
// The internal trace runs over IP datagrams, which may arrive reordered or duplicated.
type tcpRxPart struct {
	seq  uint32
	data []byte
}
type tcpReceiveState struct {
	next         uint32
	pending      []tcpRxPart
	pendingBytes int
	finAt        uint32
	hasFin, eof  bool
}

const tcpReceiveLimit = 512 * 1024

func (r *tcpReceiveState) push(seq uint32, data []byte, fin bool) []byte {
	if r.eof {
		return nil
	}
	if fin {
		at := seq + uint32(len(data))
		if distance := int32(at - r.next); distance >= 0 && distance <= tcpReceiveLimit {
			if !r.hasFin {
				r.finAt, r.hasFin = at, true
			}
		}
	}
	var out []byte
	if len(data) > 0 {
		if distance := int32(seq - r.next); distance < 0 {
			trim := -int64(distance)
			if trim >= int64(len(data)) {
				data = nil
			} else {
				data = data[trim:]
				seq = r.next
			}
		}
		if len(data) > 0 && seq == r.next {
			out = append(out, data...)
			r.next += uint32(len(data))
		} else if len(data) > 0 && int32(seq-r.next) > 0 && uint32(seq-r.next) <= tcpReceiveLimit {
			duplicate := false
			for _, part := range r.pending {
				if part.seq == seq && len(part.data) >= len(data) {
					duplicate = true
					break
				}
			}
			if !duplicate && len(r.pending) < 128 && r.pendingBytes+len(data) <= tcpReceiveLimit {
				r.pending = append(r.pending, tcpRxPart{seq, append([]byte(nil), data...)})
				r.pendingBytes += len(data)
			}
		}
	}
	for {
		advanced := false
		for index, part := range r.pending {
			distance := int32(part.seq - r.next)
			if distance > 0 {
				continue
			}
			r.pending = append(r.pending[:index], r.pending[index+1:]...)
			r.pendingBytes -= len(part.data)
			trim := -int64(distance)
			if trim < int64(len(part.data)) {
				tail := part.data[trim:]
				out = append(out, tail...)
				r.next += uint32(len(tail))
			}
			advanced = true
			break
		}
		if !advanced {
			break
		}
	}
	if r.hasFin && r.finAt == r.next {
		r.next++
		r.eof = true
	}
	return out
}
