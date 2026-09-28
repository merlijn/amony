import {Constants, SessionContext} from "../../api/Constants";
import DialogWindow from "../common/DialogWindow";
import InfoTable, {InfoRow} from "../common/InfoTable";
import './ConfigMenu.scss';
import {useContext, useEffect, useState} from "react";
import {adminReComputeHashes, adminRefreshBucket, adminReindexBucket, adminRescanMetaData, getBuckets} from "../../api/generated";
import {useLocalStorage} from "usehooks-ts";
import {useTheme} from "../../ThemeContext";
import {GridAspectRatio, GridOrientation, ThemeSetting} from "../../api/Model";
import {BucketDto} from "../../api/generated/model/bucketDto";
import * as Tabs from "@radix-ui/react-tabs";

const ConfigMenu = () => {

  // const [prefs, setPrefs] = useLocalStoragePrefs<Prefs>("prefs-v1", Constants.defaultPreferences)
  const [prefs, setPrefs, removeValue] = useLocalStorage(Constants.preferenceKey, Constants.defaultPreferences)
  const { themeSetting, setTheme } = useTheme();

  const themeOptions: Array<{value: ThemeSetting, label: string}> = [
    { value: 'system', label: 'System' },
    { value: 'light', label: 'Light' },
    { value: 'dark', label: 'Dark' },
  ]

  const aspectRatioOptions: Array<{value: GridAspectRatio, label: string}> = [
    { value: '2/1', label: '2 / 1' },
    { value: '16/9', label: '16 / 9' },
    { value: '3/2', label: '3 / 2' },
    { value: '5/4', label: '5 / 4' },
    { value: '1/1', label: '1 / 1' },
  ]

  const orientationOptions: Array<{value: GridOrientation, label: string}> = [
    { value: 'landscape', label: 'Landscape' },
    { value: 'portrait', label: 'Portrait' },
  ]

  const gridAspectRatio = prefs.gridAspectRatio ?? '16/9'
  const gridOrientation = prefs.gridOrientation ?? 'landscape'
  const gridCellWidth = typeof prefs.gridCellWidth === 'number'
    ? prefs.gridCellWidth
    : Constants.defaultPreferences.gridCellWidth

  const updatePrefs = (values: {}) => { setPrefs({...prefs, ...values} ) }
  const session = useContext(SessionContext)

  return(
      <DialogWindow>
        <Tabs.Root defaultValue="gridview">
          <Tabs.List className="tabs-list">
            <Tabs.Trigger className="tab-trigger" value="gridview">GridView</Tabs.Trigger>
            {session.isAdmin() && <Tabs.Trigger className="tab-trigger" value="admin">Admin</Tabs.Trigger>}
          </Tabs.List>

          <Tabs.Content className="tab-content" value="gridview">
            <InfoTable className="config-form">
              <InfoRow label="Grid size">
                <div className="column-slider">
                  <span className="column-slider-label">Small</span>
                  <input
                    type="range"
                    min={Constants.minGridCellWidth}
                    max={Constants.maxGridCellWidth}
                    value={gridCellWidth}
                    onChange={(e) => {
                      updatePrefs({gridCellWidth: parseInt(e.target.value, 10)})
                    }}
                  />
                  <span className="column-slider-label">Large</span>
                </div>
              </InfoRow>

              <InfoRow label="Grid aspect ratio">
                <div className="aspect-ratio-select">
                  <select
                    name="aspect-ratio"
                    value={gridAspectRatio}
                    onChange={(e) => {
                      updatePrefs({gridAspectRatio: e.target.value as GridAspectRatio})
                    }}
                  >
                    {aspectRatioOptions.map((option) => (
                      <option key={option.value} value={option.value} label={option.label} />
                    ))}
                  </select>
                  <div className="orientation-select">
                    {orientationOptions.map((option) => (
                      <label key={option.value} className="orientation-option">
                        <input
                          type="radio"
                          name="orientation-option"
                          value={option.value}
                          checked={gridOrientation === option.value}
                          onChange={() => updatePrefs({gridOrientation: option.value})}
                        />
                        <span>{option.label}</span>
                      </label>
                    ))}
                  </div>
                </div>
              </InfoRow>

              <InfoRow label="Theme">
                <div className="theme-select">
                  {themeOptions.map((option) => (
                    <label key={option.value} className="theme-option">
                      <input
                        type="radio"
                        name="theme-option"
                        value={option.value}
                        checked={themeSetting === option.value}
                        onChange={() => setTheme(option.value)}
                      />
                      <span>{option.label}</span>
                    </label>
                  ))}
                </div>
              </InfoRow>

              <InfoRow label="Show info bar">
                <input
                  type="checkbox"
                  checked={prefs.showTitles}
                  onChange={(e) => {
                    updatePrefs({showTitles: !prefs.showTitles})
                  }}
                />
              </InfoRow>

              <InfoRow label="Show video duration">
                <input
                    type="checkbox"
                    checked={prefs.showDuration}
                    onChange={(e) => {
                      updatePrefs({showDuration: !prefs.showDuration})
                    }}
                />
              </InfoRow>

              <InfoRow label="Show dates">
                <input
                    type="checkbox"
                    checked = { prefs.showDates }
                    onChange={(e) => {
                      updatePrefs({showDates: !prefs.showDates})
                    }}
                />
              </InfoRow>

              <InfoRow label="Show resolution">
                <input
                    type="checkbox"
                    checked = { prefs.showResolution }
                    onChange = {(e) => {
                      updatePrefs({showResolution: !prefs.showResolution})
                    }}
                />
              </InfoRow>
            </InfoTable>
          </Tabs.Content>

          {session.isAdmin() && (
            <Tabs.Content className="tab-content" value="admin">
              <InfoTable className="config-form">
                <AdminOptions />
              </InfoTable>
            </Tabs.Content>
          )}
        </Tabs.Root>
      </DialogWindow>
  )
}

type AdminAction = 'refresh' | 'reindex' | 'rescan' | 'recompute'

const AdminOptions = () => {
  const [buckets, setBuckets] = useState<BucketDto[]>([]);
  const [selectedBucket, setSelectedBucket] = useState<string>('');
  const [activeAction, setActiveAction] = useState<AdminAction | null>(null);

  useEffect(() => {
    getBuckets().then((data) => {
      setBuckets(data);
      if (data.length > 0) {
        setSelectedBucket(data[0].bucketId);
      }
    });
  }, []);

  const runAction = (action: AdminAction, apiCall: () => Promise<unknown>) => {
    setActiveAction(action)
    apiCall().finally(() => setActiveAction(null))
  }

  const isLoading = (action: AdminAction) => activeAction === action
  const anyLoading = activeAction !== null

  type BtnProps = { action: AdminAction; apiCall: () => Promise<unknown>; label?: string }

  const ActionButton = ({ action, apiCall, label = "Go" }: BtnProps) => (
    <button
      disabled={isLoading(action)}
      onClick={() => runAction(action, apiCall)}
    >
      {isLoading(action) ? <span className="admin-spinner" /> : label}
    </button>
  )

  return(
    <>
      <InfoRow label="Bucket">
        <select
          value={selectedBucket}
          onChange={(e) => setSelectedBucket(e.target.value)}
          disabled={anyLoading}
        >
          {buckets?.map((bucket: BucketDto) => (
            <option key={bucket.bucketId} value={bucket.bucketId}>
              {bucket.bucketId}
            </option>
          ))}
        </select>
      </InfoRow>
      <InfoRow label="Refresh resources">
        <ActionButton
          action="refresh"
          apiCall={() => adminRefreshBucket({'bucketId': selectedBucket})}
        />
      </InfoRow>
      <InfoRow label="Reindex resources">
        <ActionButton
          action="reindex"
          apiCall={() => adminReindexBucket({'bucketId': selectedBucket})}
        />
      </InfoRow>
      <InfoRow label="Rescan metadata">
        <ActionButton
          action="rescan"
          apiCall={() => adminRescanMetaData({'bucketId': selectedBucket})}
        />
      </InfoRow>
      <InfoRow label="ReCompute hashes">
        <ActionButton
          action="recompute"
          apiCall={() => adminReComputeHashes({'bucketId': selectedBucket})}
        />
      </InfoRow>
    </>
  )
}

export default ConfigMenu
