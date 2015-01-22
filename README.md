# versions-maven-plugin
Modified [versions-maven-plugin](http://mojo.codehaus.org/versions-maven-plugin/) to support incrementing
project version and adding / removing SNAPSHOT suffix.

This plugin is based on
[autoincrement-versions-maven-plugin](https://code.google.com/p/autoincrement-versions-maven-plugin/), which has been
forked from official
[versions-maven-plugin](http://mojo.codehaus.org/versions-maven-plugin/).

I have checked out latest release of versions-maven-plugin (2.1) and reintegrated changes from
autoincrement-versions-maven-plugin. Next I have added new functionalities - add and remove SNAPSHOT suffix.

## Installation
Clone this plugin project and install it into your local repository using standard maven functionality

```
mvn clean install
```

## Usage
### Increment project version
`<version>1.3.4</version>` ⇒ `<version>1.3.5</version>` or

`<version>1.3.4-SNAPSHOT</version>` ⇒ `<version>1.3.5-SNAPSHOT</version>`
```
mvn org.codehaus.mojo:versions-maven-plugin:2.1-with-increment:increment
```

### Remove -SNAPSHOT suffix from project version
`<version>1.3.4-SNAPSHOT</version>` ⇒ `<version>1.3.4</version>`
```
mvn org.codehaus.mojo:versions-maven-plugin:2.1-with-increment:remove-snapshot
```

### Append -SNAPSHOT suffix to project version
`<version>1.3.4</version>` ⇒ `<version>1.3.4-SNAPSHOT</version>`
```
mvn org.codehaus.mojo:versions-maven-plugin:2.1-with-increment:add-snapshot
```

## Credits
Pete Cornish (autoincrement-versions-maven-plugin)
