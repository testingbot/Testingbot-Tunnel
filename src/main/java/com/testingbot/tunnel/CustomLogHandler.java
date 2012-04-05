/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.testingbot.tunnel;


/**
 *
 * @author jochen
 */
public class CustomLogHandler {
    private static CustomLogHandler instance;
    private String level = "INFO";
    
    private CustomLogHandler() {
        
    }
    
    public void log(String level, String message) {
        
    }
    
    public void setLevel(String level) {
        this.level = level;
    }
    
    public static CustomLogHandler getInstance() {
        if (CustomLogHandler.instance == null) {
            CustomLogHandler.instance = new CustomLogHandler();
        }
        
        return CustomLogHandler.instance;
    }
}
