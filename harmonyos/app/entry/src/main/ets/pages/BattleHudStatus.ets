import { BattleHudElementContent } from './BattleHudElement';

@Entry
@Component
struct BattleHudStatus {
  build() { Stack() { BattleHudElementContent({ element: 'STATUS' }) }.width('100%').height('100%') }
}
