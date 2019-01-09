package com.rapid7.insightappsec.intg.jenkins.api.app;

import com.rapid7.insightappsec.intg.jenkins.api.Identifiable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class App extends Identifiable {

    private String name;

    @Builder
    public App(String id,
               String name) {
        super(id);
        this.name = name;
    }
}
