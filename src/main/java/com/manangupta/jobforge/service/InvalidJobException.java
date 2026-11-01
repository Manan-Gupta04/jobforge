package com.manangupta.jobforge.service;

/** A submission or state transition that the engine refuses (400-class error). */
public class InvalidJobException extends RuntimeException {

    public InvalidJobException(String message) {
        super(message);
    }
}
