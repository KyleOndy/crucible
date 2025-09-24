# Setting Default Field Values (Story Points & Fix Versions)

You can configure Crucible to automatically set story points and fix versions when creating tickets using the `qs` command.

## Configuration

Add these settings to your `.crucible.edn` file:

```clojure
{:jira {:default-story-points 3
        :story-points-field "customfield_10002"
        :default-fix-version-id "10100"}}
```

## Finding Your Story Points Field ID

Story points are stored in a custom field. To find the correct field ID for your Jira instance:

### Method 1: Use Crucible's field discovery

```bash
# This will show you available fields and their IDs
bb -cp core -e "(require '[lib.jira :as j] '[lib.config :as c])
                (let [config (c/load-config)]
                  (j/get-create-metadata (:jira config) \"YOUR-PROJECT\" \"Story\"))"
```

Look for fields with names like "Story Points", "Story Point Estimate", or similar.

### Method 2: Check an existing story ticket

1. Open any Story ticket in your browser
2. Look at the URL - it should be like `https://your-jira.com/browse/PROJ-123`
3. Change the URL to `https://your-jira.com/rest/api/2/issue/PROJ-123`
4. Look for fields starting with `customfield_` that contain numeric values
5. The story points field typically has a name like "Story Points" or "Story Point Estimate"

### Method 3: Common field IDs

Story points are commonly found in these custom fields:
- `customfield_10002`
- `customfield_10004`
- `customfield_10008`
- `customfield_10016`

You can try each one until you find the right field for your instance.

## Finding Your Fix Version ID

Fix versions use standard Jira version IDs. To find the correct ID for your project:

### Method 1: Use Crucible's field discovery

```bash
# Get project versions and their IDs
bb -cp core -e "(require '[lib.jira :as j] '[lib.config :as c])
                (let [config (c/load-config)
                      response (j/jira-request (:jira config) :get \"/project/YOUR-PROJECT/versions\")]
                  (:body response))"
```

This will show all versions for your project with their IDs and names.

### Method 2: Check an existing ticket with fix version

1. Open a ticket that has a fix version set
2. Change the URL to `https://your-jira.com/rest/api/2/issue/PROJ-123`
3. Look for the `fixVersions` field - it will show the ID and name:
   ```json
   "fixVersions": [{"id": "10100", "name": "2.1.0"}]
   ```

### Method 3: Using the browser network tab

1. Open your project's "Versions" page in Jira
2. Open browser developer tools (F12) → Network tab
3. Refresh the page
4. Look for API calls to `/version` endpoints
5. The response will show version IDs and names

## Example Configuration

```clojure
;; Example .crucible.edn
{:jira {:base-url "https://your-company.atlassian.net"
        :username "your.email@company.com"
        :api-token "pass:jira/api-token"
        :default-project "PROJ"
        :default-issue-type "Story"
        :default-story-points 5      ; Default story points for new tickets
        :story-points-field "customfield_10002"  ; Your story points field ID
        :default-fix-version-id "10100"          ; Default fix version ID
        :auto-assign-self true
        :auto-add-to-sprint true}}
```

## How It Works

When you create a ticket with `c qs "My story"`, Crucible will:

1. Create the ticket with your title and description
2. If `default-story-points` is set AND `story-points-field` is configured:
   - Add the default story points to the ticket
   - Only if story points aren't already specified in `custom-fields`
3. If `default-fix-version-id` is configured:
   - Add the fix version to the ticket
   - This is a standard Jira field, not a custom field
4. Apply any other configured custom fields

## Alternative: Using Custom Fields Directly

You can also set story points (and override the default) using the custom fields configuration:

```clojure
{:jira {:custom-fields {"customfield_10002" 8}}}  ; Always use 8 story points
```

The `custom-fields` approach takes precedence over `default-story-points`.

**Note**: Fix versions cannot be overridden through custom fields since it's a standard Jira field. If you need different fix versions per ticket, omit `default-fix-version-id` from your config and set them manually.

## Validation

To test your configuration:

```bash
# Dry run to see what would be created
c qs "Test story" --dry-run

# This will show you the story points and fix version values that would be set
```

If story points and fix versions appear in the dry run output, your configuration is working correctly.

## Key Differences: Story Points vs Fix Versions

| Aspect | Story Points | Fix Versions |
|--------|-------------|--------------|
| **Field Type** | Custom field | Standard Jira field |
| **Configuration** | Requires field ID + value | Just the version ID |
| **Location in API** | `customfield_XXXXX` | `fixVersions` |
| **Override Method** | Via `custom-fields` config | Cannot override (omit from config instead) |
| **Value Format** | Number (e.g., `5`) | Version ID string (e.g., `"10100"`) |
| **Multiple Values** | Single value | Supports multiple (but config sets one) |

Both features work together and can be used simultaneously.