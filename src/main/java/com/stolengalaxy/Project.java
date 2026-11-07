package com.stolengalaxy;

import java.util.List;

public record Project(List<User> users, List<Task> tasks, String projectDescription, String repoURL){

}
