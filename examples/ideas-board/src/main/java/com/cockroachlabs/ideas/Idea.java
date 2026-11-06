package com.cockroachlabs.ideas;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

// tag::entity[]
@Entity
@Table(name = "ideas")
public class Idea {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String text;

    @Column(nullable = false)
    private int votes;

    protected Idea() {
    }

    public Idea(UUID id, String text, int votes) {
        this.id = id;
        this.text = text;
        this.votes = votes;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public int getVotes() {
        return votes;
    }

    public void setVotes(int votes) {
        this.votes = votes;
    }
}
// end::entity[]
