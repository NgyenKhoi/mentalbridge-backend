param(
  [Parameter(Mandatory = $true)]
  [string]$Path
)

$ErrorActionPreference = "Stop"
$resolvedPath = Resolve-Path -LiteralPath $Path -ErrorAction Stop
$rows = @(Import-Csv -LiteralPath $resolvedPath)
$errors = [System.Collections.Generic.List[string]]::new()

if ($rows.Count -eq 0) {
  throw "CSV contains no data rows: $resolvedPath"
}

$expectedHeaders = @(
  "Issue Type",
  "Issue ID",
  "Summary",
  "Parent",
  "Assignee",
  "Story Points",
  "Original Estimate",
  "Sprint",
  "Priority",
  "Labels",
  "Description"
)
$actualHeaders = @($rows[0].PSObject.Properties.Name)

if (($actualHeaders -join "|") -ne ($expectedHeaders -join "|")) {
  $errors.Add("Headers must exactly match: $($expectedHeaders -join ',')")
}

$levels = @{
  Epic = 1
  Story = 0
  Subtask = -1
}
$requiredFields = @(
  "Issue Type",
  "Issue ID",
  "Summary",
  "Sprint",
  "Priority",
  "Labels",
  "Description"
)
$rowsById = @{}
$rowNumberById = @{}

for ($index = 0; $index -lt $rows.Count; $index++) {
  $row = $rows[$index]
  $rowNumber = $index + 2
  $id = $row."Issue ID"

  foreach ($field in $requiredFields) {
    if ([string]::IsNullOrWhiteSpace($row.$field)) {
      $errors.Add("Row $rowNumber is missing required field '$field'.")
    }
  }

  if ($rowsById.ContainsKey($id)) {
    $errors.Add("Duplicate Issue ID '$id' at row $rowNumber.")
  } else {
    $rowsById[$id] = $row
    $rowNumberById[$id] = $rowNumber
  }

  if (-not $levels.ContainsKey($row."Issue Type")) {
    $errors.Add("Row $rowNumber has unsupported Issue Type '$($row.'Issue Type')'; use Epic, Story, or Subtask.")
  }

  if ($row."Issue Type" -eq "Story") {
    $storyPoints = 0
    if (-not [int]::TryParse($row."Story Points", [ref]$storyPoints) -or $storyPoints -le 0) {
      $errors.Add("Story '$id' must have positive integer Story Points.")
    }
    if (-not [string]::IsNullOrWhiteSpace($row."Original Estimate")) {
      $errors.Add("Story '$id' must leave Original Estimate blank; estimate its Subtasks instead.")
    }
  } elseif ($row."Issue Type" -eq "Subtask") {
    if (-not [string]::IsNullOrWhiteSpace($row."Story Points")) {
      $errors.Add("Subtask '$id' must leave Story Points blank.")
    }
    $estimate = 0L
    if (-not [long]::TryParse($row."Original Estimate", [ref]$estimate) -or $estimate -le 0) {
      $errors.Add("Subtask '$id' must have a positive Original Estimate in seconds.")
    }
  } elseif ($row."Issue Type" -eq "Epic") {
    if (-not [string]::IsNullOrWhiteSpace($row."Story Points")) {
      $errors.Add("Epic '$id' must leave Story Points blank.")
    }
    if (-not [string]::IsNullOrWhiteSpace($row."Original Estimate")) {
      $errors.Add("Epic '$id' must leave Original Estimate blank.")
    }
  }

  $description = $row.Description
  if ($description -notmatch "(?m)^## Objective\s*$") {
    $errors.Add("Work item '$id' is missing an Objective section.")
  }
  if ($description -notmatch "(?m)^## Definition of Done\s*$") {
    $errors.Add("Work item '$id' is missing a Definition of Done section.")
  } elseif ($description -notmatch "(?ms)^## Definition of Done\s*.*?^- \[ \]") {
    $errors.Add("Work item '$id' Definition of Done must contain checklist items.")
  }

  if ($row."Issue Type" -in @("Story", "Subtask") -and $description -notmatch "(?m)^## Acceptance Criteria\s*$") {
    $errors.Add("Work item '$id' is missing an Acceptance Criteria section.")
  }

  if ($row."Issue Type" -eq "Story") {
    if ($description -notmatch "(?m)^## Evidence\s*$") {
      $errors.Add("Story '$id' is missing an Evidence section.")
    }
    if ($description -notmatch "(?m)^## Completion Notes\s*$") {
      $errors.Add("Story '$id' is missing a Completion Notes section.")
    }
  }
}

foreach ($row in $rows) {
  $id = $row."Issue ID"
  $type = $row."Issue Type"
  $parentId = $row.Parent

  if ($type -eq "Epic") {
    if (-not [string]::IsNullOrWhiteSpace($parentId)) {
      $errors.Add("Epic '$id' must not have a Parent.")
    }
    continue
  }

  if ([string]::IsNullOrWhiteSpace($parentId)) {
    $errors.Add("$type '$id' must have a Parent.")
    continue
  }

  if (-not $rowsById.ContainsKey($parentId)) {
    $errors.Add("$type '$id' references missing Parent '$parentId'.")
    continue
  }

  $parent = $rowsById[$parentId]
  if ($levels.ContainsKey($type) -and $levels.ContainsKey($parent."Issue Type")) {
    if ($levels[$parent."Issue Type"] -ne ($levels[$type] + 1)) {
      $errors.Add("Invalid hierarchy: $($parent.'Issue Type') '$parentId' cannot parent $type '$id'.")
    }
  }

  if ($rowNumberById[$parentId] -ge $rowNumberById[$id]) {
    $errors.Add("Parent '$parentId' must appear before child '$id'.")
  }
}

$stories = @($rows | Where-Object { $_."Issue Type" -eq "Story" })
foreach ($story in $stories) {
  $children = @($rows | Where-Object { $_."Issue Type" -eq "Subtask" -and $_.Parent -eq $story."Issue ID" })
  if ($children.Count -eq 0) {
    $errors.Add("Story '$($story.'Issue ID')' has no Subtasks.")
  }
}

if ($errors.Count -gt 0) {
  foreach ($validationError in $errors) {
    Write-Error $validationError -ErrorAction Continue
  }
  exit 1
}

$storyPointTotal = ($stories | ForEach-Object { [int]$_."Story Points" } | Measure-Object -Sum).Sum
$estimateSeconds = ($rows | Where-Object { $_."Issue Type" -eq "Subtask" } | ForEach-Object { [long]$_."Original Estimate" } | Measure-Object -Sum).Sum

Write-Output "VALID Jira task CSV: $resolvedPath"
Write-Output "Rows: $($rows.Count)"
Write-Output "Epics: $(@($rows | Where-Object { $_.'Issue Type' -eq 'Epic' }).Count)"
Write-Output "Stories: $($stories.Count)"
Write-Output "Subtasks: $(@($rows | Where-Object { $_.'Issue Type' -eq 'Subtask' }).Count)"
Write-Output "Story Points: $storyPointTotal"
Write-Output "Original Estimate Hours: $($estimateSeconds / 3600)"
Write-Output "Jira mapping: Epic -> Epic; Story -> Story; Subtask -> Subtask"
