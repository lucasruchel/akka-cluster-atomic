package messages;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.io.Serializable;

public class ABCast<D> implements Message {
    private static final long serialVersionUID = 5L;


    D data;

    @JsonCreator
    public ABCast(D data) {
        this.data = data;
    }

    public D getData() {
        return data;
    }

    public void setData(D data) {
        this.data = data;
    }
}
