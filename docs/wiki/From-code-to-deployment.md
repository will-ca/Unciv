# From code to deployment

So, your code works! You've solved all the bugs and now you just need to get it out to everyone!

So, how does THAT work?

The process has two major parts, one is "Getting your code in the main repository" and the other is "Deploying versions" - as a developer, you'll be taking an active part in the first process, but the second process is on me =)

## Getting your code in the main repo

* First off, push your changes with Git to your own branch at https://github.com/YourUsername/Unciv.git. I hope you've been doing this during development too, but that's none of my business \*sips tea\*
* Issue a pull request from https://github.com/YourUsername/Unciv - from the Pull Requests is the simplest
* The Travis build will check that your proposed change builds properly and passes all tests
* I'll go over your pull request and will ask questions and request changes - this is not only for code quality and standard, it's mostly so you can learn how the repo works for the next change you make =)
* When everything looks good, I'll merge your code in and it'll enter the next release!

## Deploying versions

When I'm ready to release a new version I:
* Comment "merge translations" in one of the open PRs tagged as 'mergeable translation' to trigger the translation branch creation, add a "summary" comment to trigger summary generation, merge the PR and delete the branch (so next version translation branch starts fresh)
* From my workstation - pull the latest changes and run the [translation generation](./Translating.md#translation-generation---for-developers)
* Change the versionCode and versionName in the Android build.gradle so that Google Play and F-droid can recognize that it's a different release
* Add an entry in the changelog.md done, WITHOUT hashtags, and less than 500 characters (that's the limit for Google play entries). The formatting needs to be exact or the text sent to Discord, the Github release etc. won't be complete.
* Add a tag to the commit of the version. When the [Github action](https://github.com/yairm210/Unciv/actions/workflows/buildAndDeploy.yml) sees that we've added a tag, it will run a build, and this time (because of the configuration we put in the [yml file](/.github/workflows/buildAndDeploy.yml) file), it will:
   * Pack a .jar file, which will work for every operating system with Java
   * Use Linux and Windows JDKs to create standalone zips for 32 and 64 bit systems, because we can't rely on the fact that users will have a JRE
   * Download [Butler](https://itch.io/docs/butler/installing.html) and use it to [push](https://itch.io/docs/butler/pushing.html) the new versions to the [itch.io page](https://yairm210.itch.io/unciv)
   * Read the changelog.md file to get the changes for the latest version
   * Upload all of these files to a new release on Github, with the release notes, which will get added to the [Releases](https://github.com/yairm210/Unciv/releases) page
   * Send an announcement on the Discord server of the version release and release notes via webhook
   * Pack, Sign, and Upload a new APK to the Google Play Console at 10% rollout
* The F-Droid bot checks periodically if we added a new tag. When it recognizes that we did, it will update the [yaml file here](https://gitlab.com/fdroid/fdroiddata/blob/master/metadata/com.unciv.app.yml)
   * When the bot next runs and sees that there's a version it doesn't have a release for, it will attempt to build the new release. The log of the build will be added [here](https://f-droid.org/wiki/page/com.unciv.app/lastbuild) (redirects to the latest build), and the new release will eventually be available [here](https://f-droid.org/en/packages/com.unciv.app/)

## About Google Play publishing

+We start at a 10% rollout, after a day with no major problems go to 30%, and after another day to 100%. If you were counting that means that most players will get the new version after 2+ days.
+
+If there were problems, we halt the current rollout, fix the problems, and release a patch version, which starts at 10% again.
+
+Dear future me - the automation was extremely annoying guesswork to set up, so the facts you need to know are:
- There is a user at the [Google Cloud Platform Account Manager](https://console.cloud.google.com/iam-admin/iam) called  Unciv_Upload_Account. There is an access key to this account, in json, stored as the Github secret GOOGLE_PLAY_SERVICE_ACCOUNT_JSON.
- This user was granted ADMIN permissions to the Google Play (after much trial and error since nothing else seemed to work) under User > Users and Permissions. Under Manage > Account permissions, you can see that it has Admin.

## Updating the wiki

Pages for the [Unciv Github Wiki](https://github.com/yairm210/Unciv/wiki/) are kept in the main repository under [/docs/wiki](/docs/wiki).

The process to edit the wiki is as follows:

1. Open a pull request in the main Unciv repository that changes files under [/docs/wiki](/docs/wiki).
2. Once the pull request is merged, an account with commit privileges on the Unciv repository leaves a comment saying "`update wiki`".
3. This comment triggers a bot to copy all the wiki files from the main repository into the Github wiki, with a link back to the PR in its commit message for credit.

Doing things this way has several distinct advantages over using the Github Wiki web interface directly:

* Changes can be proposed via PR and proofread or fact-checked.
* A proper MarkDown editor or IDE can be used to write the wiki, bringing faster editing, clickable links while editing, better live HTML preview, and automatic detection of problems like broken links.
* The wiki files can also be browsed at https://github.com/yairm210/Unciv/tree/master/docs/wiki.
* Auto-generated documentation made by the build process can be placed directly in the wiki.

However, it also imposes a couple of conventions about how links should best be formatted:

|Link type|Format|Example|
|---|---|---|
|Inter-wiki|Should begin with "./", and include ".md".|[`./Mods.md#other`](./Mods.md#other)|
|Code or asset file|Should begin with "/", and be relative to the project root.|[`/android/assets/game.png`](/android/assets/game.png)|

These formats will allow IDEs like Android studio to resolve these links and check for broken links, while also working on the [Github code browser](https://github.com/yairm210/Unciv/tree/master/docs/wiki).

The bot that updates the wiki from the main repository automatically translates them into formats that are compatible with Github Wikis, which have somewhat non-standard requirements.
