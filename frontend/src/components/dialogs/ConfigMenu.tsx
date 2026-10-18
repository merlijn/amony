import {Constants, SessionContext} from "../../api/Constants";
import DialogWindow from "../common/DialogWindow";
import InfoTable, {InfoRow} from "../common/InfoTable";
import './ConfigMenu.scss';
import {useContext} from "react";
import {GridAspectRatio, GridOrientation, GridPrefs, ListViewDefaultAction, ThemeSetting, usePrefs} from "../../api/Prefs";
import {useTheme} from "../../ThemeContext";
import * as Tabs from "@radix-ui/react-tabs";
import ChoiceOption from "../common/ChoiceOption";
import BucketsConfig from "./BucketsConfig";

const ConfigMenu = () => {

  const [prefs, updatePrefs] = usePrefs()
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
    { value: 'golden', label: 'Golden ratio' },
  ]

  const orientationOptions: Array<{value: GridOrientation, label: string}> = [
    { value: 'landscape', label: 'Landscape' },
    { value: 'portrait', label: 'Portrait' },
  ]

  const gridAspectRatio = prefs.grid.aspectRatio
  const gridOrientation = prefs.grid.orientation
  const gridCellWidth   = prefs.grid.cellWidth
  const listViewPrefs   = prefs.listView

  const updateGrid = (values: Partial<GridPrefs>) => updatePrefs({grid: {...prefs.grid, ...values}})

  const session = useContext(SessionContext)

  return(
      <DialogWindow>
        <Tabs.Root defaultValue="general">
          <Tabs.List className="tabs-list">
            <Tabs.Trigger className="tab-trigger" value="general">General</Tabs.Trigger>
            <Tabs.Trigger className="tab-trigger" value="gridview">GridView</Tabs.Trigger>
            <Tabs.Trigger className="tab-trigger" value="listview">ListView</Tabs.Trigger>
            {session.isAdmin() && <Tabs.Trigger className="tab-trigger" value="buckets">Buckets</Tabs.Trigger>}
          </Tabs.List>

          <Tabs.Content className="tab-content" value="general">
            <InfoTable className="config-form">
              <InfoRow label="Theme">
                <div className="theme-select">
                  {themeOptions.map((option) => (
                    <ChoiceOption
                      key={option.value}
                      type="radio"
                      name="theme-option"
                      value={option.value}
                      checked={themeSetting === option.value}
                      onChange={() => setTheme(option.value)}
                      label={option.label}
                    />
                  ))}
                </div>
              </InfoRow>
            </InfoTable>
          </Tabs.Content>

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
                      updateGrid({cellWidth: parseInt(e.target.value, 10)})
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
                      updateGrid({aspectRatio: e.target.value as GridAspectRatio})
                    }}
                  >
                    {aspectRatioOptions.map((option) => (
                      <option key={option.value} value={option.value} label={option.label} />
                    ))}
                  </select>
                  <div className="orientation-select">
                    {orientationOptions.map((option) => (
                      <ChoiceOption
                        key={option.value}
                        type="radio"
                        name="orientation-option"
                        value={option.value}
                        checked={gridOrientation === option.value}
                        onChange={() => updateGrid({orientation: option.value})}
                        label={option.label}
                      />
                    ))}
                  </div>
                </div>
              </InfoRow>

              <InfoRow label="Show info bar">
                <ChoiceOption
                  type="checkbox"
                  checked={prefs.grid.showTitles}
                  onChange={() => updateGrid({showTitles: !prefs.grid.showTitles})}
                />
              </InfoRow>

              <InfoRow label="Show video duration">
                <ChoiceOption
                  type="checkbox"
                  checked={prefs.grid.showDuration}
                  onChange={() => updateGrid({showDuration: !prefs.grid.showDuration})}
                />
              </InfoRow>

              <InfoRow label="Show dates">
                <ChoiceOption
                  type="checkbox"
                  checked={prefs.grid.showDates}
                  onChange={() => updateGrid({showDates: !prefs.grid.showDates})}
                />
              </InfoRow>

              <InfoRow label="Show resolution">
                <ChoiceOption
                  type="checkbox"
                  checked={prefs.grid.showResolution}
                  onChange={() => updateGrid({showResolution: !prefs.grid.showResolution})}
                />
              </InfoRow>
            </InfoTable>
          </Tabs.Content>

          <Tabs.Content className="tab-content" value="listview">
            <InfoTable className="config-form">
              <InfoRow label="Default row action">
                <select
                  name="list-view-default-action"
                  value={listViewPrefs.defaultAction}
                  onChange={(e) => {
                    updatePrefs({listView: {...prefs.listView, defaultAction: e.target.value as ListViewDefaultAction}})
                  }}
                >
                  <option value="view" label="Play / view" />
                  <option value="show-info" label="Show info" />
                </select>
              </InfoRow>
            </InfoTable>
          </Tabs.Content>

          {session.isAdmin() && (
            <Tabs.Content className="tab-content" value="buckets">
              <BucketsConfig />
            </Tabs.Content>
          )}
        </Tabs.Root>
      </DialogWindow>
  )
}

export default ConfigMenu
