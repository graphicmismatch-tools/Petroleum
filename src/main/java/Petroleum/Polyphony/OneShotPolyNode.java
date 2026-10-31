package Petroleum.Polyphony;

import Petroleum.Graph.Node;
import Petroleum.Graph.OneShot;

import java.util.List;

final class OneShotPolyNode extends PolyNode implements OneShot {

    OneShotPolyNode(List<Node> voices, String stamp) {
        super(voices, stamp);
    }

    @Override
    public boolean isIdle() {
        for (Node voice : voices()) {
            if (!((OneShot) voice).isIdle()) {
                return false;
            }
        }
        return true;
    }
}
